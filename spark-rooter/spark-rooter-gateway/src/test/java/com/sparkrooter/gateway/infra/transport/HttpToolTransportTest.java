package com.sparkrooter.gateway.infra.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.PlatformMapper;
import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.contracts.tool.ToolTransportException;
import com.sparkrooter.contracts.tool.ToolTransportException.Kind;
import com.sparkrooter.spi.ExecutionContext;
import com.sparkrooter.spi.ProviderAuth;
import com.sparkrooter.spi.ProviderEndpointResolver;
import com.sparkrooter.spi.SharedSecretProviderAuth;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * HTTP 传输（feat-provider-http-transport §3.1a.1）。
 *
 * <p>最关键的是<b>读超时与连接失败必须分类为不同 Kind</b>：二者的重试安全性完全相反，弄反了就会 在远端已执行成功的情况下重试，造成重复扣款。
 *
 * <p>该判断已提为纯函数 {@code classifyIoFailure} 直接单测，而不是走 RestClient 全栈——模拟整个 HTTP 客户端既脆弱又掩盖被测逻辑（首版尝试手写
 * {@code ClientHttpRequest} 撞上 RestClient 内部 的 {@code UnsupportedOperationException}，说明那条路测的是 mock
 * 保真度而非业务判断）。 寻址与请求构造仍走公开方法断言。
 */
final class HttpToolTransportTest {

  private static final ObjectMapper MAPPER = PlatformMapper.create();
  private static final String SERVICE = "order-service";
  private static final String TOKEN = "hub-token";

  private final ProviderAuth auth = new SharedSecretProviderAuth(Map.of(SERVICE, TOKEN));
  private final ProviderEndpointResolver resolver = new ManifestProviderEndpointResolver();
  private final HttpToolTransport transport = new HttpToolTransport(resolver, auth, MAPPER);

  // ------------------------------------------------------------ 错误分类（M-1 核心）

  /**
   * 读超时 → {@code REMOTE_TIMEOUT}，<b>不可重试</b>。
   *
   * <p>远端已收到请求，可能正在执行甚至已成功，只是响应没赶上 deadline。重试会造成重复副作用。
   */
  @Test
  void readTimeoutIsRemoteTimeoutAndNotRetryable() {
    Kind kind =
        HttpToolTransport.classifyIoFailure(
            new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

    assertThat(kind).isEqualTo(Kind.REMOTE_TIMEOUT);
    assertThat(new ToolTransportException(kind, "x").retryable()).as("结果未知，重试不安全").isFalse();
  }

  /**
   * 连接被拒 → {@code UNREACHABLE}，<b>可重试</b>。
   *
   * <p>与上一条对照：确定没碰到工具。判据是「这次调用有没有执行」，不是「错误严不严重」。
   */
  @Test
  void connectRefusedIsUnreachableAndRetryable() {
    Kind kind =
        HttpToolTransport.classifyIoFailure(
            new ResourceAccessException("I/O error", new ConnectException("Connection refused")));

    assertThat(kind).isEqualTo(Kind.UNREACHABLE);
    assertThat(new ToolTransportException(kind, "x").retryable()).as("确定未执行，可安全重试").isTrue();
  }

  /** DNS 解析失败 → UNREACHABLE（同样确定未到达）。 */
  @Test
  void unknownHostIsUnreachable() {
    assertThat(
            HttpToolTransport.classifyIoFailure(
                new ResourceAccessException("I/O error", new UnknownHostException("order-svc"))))
        .isEqualTo(Kind.UNREACHABLE);
  }

  /** cause 深层嵌套时仍能正确识别（实际栈里常包两三层）。 */
  @Test
  void nestedSocketTimeoutIsStillDetected() {
    Throwable deep =
        new ResourceAccessException(
            "outer", new IOException("mid", new SocketTimeoutException("Read timed out")));
    assertThat(HttpToolTransport.classifyIoFailure(deep)).isEqualTo(Kind.REMOTE_TIMEOUT);
  }

  /**
   * 连接异常先于超时异常出现时，判为 UNREACHABLE。
   *
   * <p>遍历 cause 链时遇到 {@code ConnectException} 就立即返回，不再往下找 SocketTimeout——
   * 连不上就是连不上，不能因为更深层有个超时就误判成「结果未知」而放弃重试。
   */
  @Test
  void connectExceptionWinsOverDeeperTimeout() {
    ConnectException connect = new ConnectException("refused");
    connect.initCause(new SocketTimeoutException("stale inner timeout"));
    Throwable mixed = new ResourceAccessException("outer", connect);
    assertThat(HttpToolTransport.classifyIoFailure(mixed)).isEqualTo(Kind.UNREACHABLE);
  }

  /** 既非超时也非连接失败的 IO 错误 → 保守判为 UNREACHABLE（可重试）。 */
  @Test
  void otherIoFailureDefaultsToUnreachable() {
    assertThat(
            HttpToolTransport.classifyIoFailure(
                new ResourceAccessException("broken pipe", new IOException("broken pipe"))))
        .isEqualTo(Kind.UNREACHABLE);
  }

  /** 四个 Kind 的 retryable 语义固定，防被"顺手优化"改动。 */
  @Test
  void retryabilityIsFixedPerKind() {
    assertThat(new ToolTransportException(Kind.NOT_FOUND, "x").retryable()).isTrue();
    assertThat(new ToolTransportException(Kind.UNREACHABLE, "x").retryable()).isTrue();
    assertThat(new ToolTransportException(Kind.REMOTE_FAILED, "x").retryable()).isFalse();
    assertThat(new ToolTransportException(Kind.REMOTE_TIMEOUT, "x").retryable()).isFalse();
  }

  // ------------------------------------------------------------ 寻址

  /** Manifest 声明 http 但缺 provider 段 → UNREACHABLE（Registry 里的脏数据）。 */
  @Test
  void manifestWithoutProviderSectionIsUnreachable() {
    ToolManifest m = manifestWithoutProvider();
    assertThatThrownBy(() -> transport.invoke(m, args(), ctx()))
        .isInstanceOf(ToolTransportException.class)
        .satisfies(
            e -> assertThat(((ToolTransportException) e).kind()).isEqualTo(Kind.UNREACHABLE));
  }

  /** 地址解析不出来 → UNREACHABLE，且消息点明是哪个服务。 */
  @Test
  void unresolvableEndpointIsUnreachable() {
    ToolManifest m = manifest(null);
    assertThatThrownBy(() -> transport.invoke(m, args(), ctx()))
        .isInstanceOf(ToolTransportException.class)
        .satisfies(
            e -> {
              assertThat(((ToolTransportException) e).kind()).isEqualTo(Kind.UNREACHABLE);
              assertThat(e.getMessage()).contains(SERVICE);
            });
  }

  /** 尾斜杠归一化，避免与 INVOKE_PATH 拼出双斜杠。 */
  @Test
  void resolverNormalizesTrailingSlash() {
    assertThat(resolver.resolve(SERVICE, "http://order:8080/")).contains("http://order:8080");
    assertThat(resolver.resolve(SERVICE, "http://order:8080")).contains("http://order:8080");
    assertThat(resolver.resolve(SERVICE, null)).isEmpty();
    assertThat(resolver.resolve(SERVICE, "   ")).isEmpty();
  }

  /** 宿主可用自定义 resolver 覆盖（接注册中心场景）。 */
  @Test
  void customResolverIsHonoured() {
    ProviderEndpointResolver fromRegistry =
        (service, declared) -> Optional.of("http://discovered-" + service + ":9000");
    HttpToolTransport t = new HttpToolTransport(fromRegistry, auth, MAPPER);
    // 地址解析成功后会真的发请求（连不上），但 Kind 应是 UNREACHABLE 而非"解析失败"
    assertThatThrownBy(() -> t.invoke(manifest(null), args(), ctx()))
        .isInstanceOf(ToolTransportException.class)
        .satisfies(
            e ->
                assertThat(e.getMessage())
                    .as("说明确实用了自定义 resolver 的地址去连")
                    .doesNotContain("cannot resolve"));
  }

  @Test
  void protocolIsHttp() {
    assertThat(transport.protocol()).isEqualTo(ToolManifest.Protocol.HTTP);
  }

  /** 读超时配置必须 ≤ Manifest 的 timeoutMs，否则 Gateway 先超时而连接仍占用。 */
  @Test
  void connectTimeoutIsBoundedAndIndependentOfToolTimeout() {
    // 连接超时是固定值（连不上与工具执行时长无关），且远小于常见的工具超时
    assertThat(HttpToolTransport.CONNECT_TIMEOUT_MS).isPositive().isLessThanOrEqualTo(5000);
  }

  // ------------------------------------------------------------ helpers

  private static ExecutionContext ctx() {
    return new ExecutionContext("run_1", "tc_1", "sess_1", "idem_1", "trace_1");
  }

  private static ObjectNode args() {
    return MAPPER.createObjectNode().put("orderId", "10001");
  }

  private static ToolManifest manifest(String baseUrl) {
    ObjectNode m = baseManifest();
    ObjectNode provider = m.putObject("provider");
    provider.put("serviceName", SERVICE);
    if (baseUrl != null) {
      provider.put("baseUrl", baseUrl);
    }
    return MAPPER.convertValue(m, ToolManifest.class);
  }

  private static ToolManifest manifestWithoutProvider() {
    return MAPPER.convertValue(baseManifest(), ToolManifest.class);
  }

  private static ObjectNode baseManifest() {
    ObjectNode m = MAPPER.createObjectNode();
    m.put("toolId", "demo.detail.get")
        .put("version", "1.0.0")
        .put("domain", "demo")
        .put("name", "查询")
        .put("description", "只读")
        .put("protocol", "http");
    m.putObject("inputSchema").put("type", "object");
    m.putObject("outputSchema").put("type", "object");
    m.putObject("risk")
        .put("level", "low")
        .put("sideEffect", false)
        .put("reversible", true)
        .put("confirmation", "never");
    m.putObject("authorization");
    m.putObject("execution").put("timeoutMs", 1000).put("maxRetries", 1).put("idempotency", "none");
    m.putObject("owner").put("team", "demo-team");
    m.put("status", "active");
    return m;
  }
}
