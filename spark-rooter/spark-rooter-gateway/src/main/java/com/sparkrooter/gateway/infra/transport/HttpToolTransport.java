package com.sparkrooter.gateway.infra.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.contracts.tool.ToolTransport;
import com.sparkrooter.contracts.tool.ToolTransportException;
import com.sparkrooter.spi.ExecutionContext;
import com.sparkrooter.spi.ProviderAuth;
import com.sparkrooter.spi.ProviderEndpointResolver;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP 传输：领域服务是独立进程（微服务形态），POST 到 provider 的执行端点。
 *
 * <p>只负责传输。Schema 校验 / 访问策略 / 幂等 / 重试 / 审计仍在 Gateway 的 {@code InvokeToolUseCase}。
 *
 * <p><b>超时与重试的跨进程语义</b>（feat-provider-http-transport §3.1a.1）：
 *
 * <ul>
 *   <li>读超时按 Manifest 的 {@code execution.timeoutMs} 逐次设置，且不超过它——否则 Gateway 的 {@code Future.get} 先炸而
 *       HTTP 连接仍挂着，耗尽连接池
 *   <li>读超时 → {@link ToolTransportException.Kind#REMOTE_TIMEOUT}（<b>结果未知</b>，Gateway 不重试）：
 *       远端可能已执行成功，只是响应没赶上，重试会造成重复副作用
 *   <li>连接失败 → {@code UNREACHABLE}（确定未到达工具，可安全重试）
 *   <li>非 2xx → {@code REMOTE_FAILED}（已执行过，是否生效未知，不重试）
 * </ul>
 *
 * <p>每次调用新建 {@link RestClient}：读超时随 Manifest 变化，而 {@code SimpleClientHttpRequestFactory}
 * 的超时是实例级的。provider 数量与调用频率下这点开销可接受；宿主要连接池就替换本 Bean。
 */
public class HttpToolTransport implements ToolTransport {

  private static final Logger log = LoggerFactory.getLogger(HttpToolTransport.class);

  /** 连接超时：固定值，与工具执行时长无关（连不上就是连不上）。 */
  static final int CONNECT_TIMEOUT_MS = 2000;

  /**
   * provider 执行端点路径。
   *
   * <p>与 provider 侧 {@code ProviderInvokeController.BASE_PATH + "/invoke"} 必须一致。两边都
   * <b>不做成可配</b>：只有一边可配会导致改了配置后 hub 静默 404（阶段 4 评审 F-3）。
   */
  static final String INVOKE_PATH = "/spark/tools/invoke";

  private final ProviderEndpointResolver resolver;
  private final ProviderAuth auth;
  private final ObjectMapper mapper;

  public HttpToolTransport(
      ProviderEndpointResolver resolver, ProviderAuth auth, ObjectMapper mapper) {
    this.resolver = resolver;
    this.auth = auth;
    this.mapper = mapper;
  }

  @Override
  public ToolManifest.Protocol protocol() {
    return ToolManifest.Protocol.HTTP;
  }

  @Override
  public JsonNode invoke(ToolManifest manifest, JsonNode args, ExecutionContext ctx) {
    ToolManifest.Provider provider = manifest.provider();
    if (provider == null) {
      // 契约的 if/then 保证 http ⇒ provider 必填；走到这里说明 Registry 里有越过校验的脏数据
      throw new ToolTransportException(
          ToolTransportException.Kind.UNREACHABLE,
          "manifest has protocol=http but no provider coordinates: " + manifest.key());
    }

    Optional<String> baseUrl = resolver.resolve(provider.serviceName(), provider.baseUrl());
    if (baseUrl.isEmpty()) {
      throw new ToolTransportException(
          ToolTransportException.Kind.UNREACHABLE,
          "cannot resolve endpoint for service " + provider.serviceName());
    }

    // 按被调服务取令牌：hub 调多个 provider，各自密钥不同
    String token = auth.outboundToken(provider.serviceName());
    if (token == null) {
      throw new ToolTransportException(
          ToolTransportException.Kind.UNREACHABLE,
          "no provider token configured for service " + provider.serviceName());
    }

    long timeoutMs = manifest.execution().timeoutMs();
    RestClient client = clientFor(baseUrl.get(), timeoutMs);
    ObjectNode body = requestBody(manifest, args, ctx);

    try {
      JsonNode out =
          client
              .post()
              .uri(INVOKE_PATH)
              .contentType(MediaType.APPLICATION_JSON)
              .header(ProviderAuth.HEADER, token)
              .body(body)
              .retrieve()
              .body(JsonNode.class);
      if (out == null) {
        throw new ToolTransportException(
            ToolTransportException.Kind.REMOTE_FAILED,
            "provider returned empty body for " + manifest.key());
      }
      return out;
    } catch (RestClientResponseException e) {
      // 远端收到了请求并给了非 2xx：已执行过，是否生效未知 → 不可重试。
      // 状态码进日志，不把远端响应体透给用户（不泄漏 provider 实现细节）
      log.warn(
          "provider_call_failed toolId={} service={} status={}",
          manifest.toolId(),
          provider.serviceName(),
          e.getStatusCode().value());
      throw new ToolTransportException(
          ToolTransportException.Kind.REMOTE_FAILED,
          "provider returned " + e.getStatusCode().value() + " for " + manifest.key(),
          e);
    } catch (ResourceAccessException e) {
      // 连接失败与读超时都是 ResourceAccessException，按 cause 区分：
      // SocketTimeoutException = 已连上但响应超时（结果未知）；其余 = 没连上（确定未执行）
      ToolTransportException.Kind kind = classifyIoFailure(e);
      log.warn(
          "provider_call_io toolId={} service={} kind={} timeoutMs={}",
          manifest.toolId(),
          provider.serviceName(),
          kind,
          timeoutMs);
      throw new ToolTransportException(
          kind, "provider " + provider.serviceName() + " " + kind.name().toLowerCase(), e);
    }
  }

  /**
   * IO 失败 → 失败类别。
   *
   * <p>提为纯函数是为了能直接单测：这是全类最容易出错、后果最严重的一处判断（弄反会在远端 已执行成功时重试，造成重复扣款），而走 RestClient 全栈去测它需要模拟整个 HTTP
   * 客户端。
   */
  static ToolTransportException.Kind classifyIoFailure(Throwable e) {
    return isReadTimeout(e)
        ? ToolTransportException.Kind.REMOTE_TIMEOUT
        : ToolTransportException.Kind.UNREACHABLE;
  }

  /**
   * 读超时是否发生。
   *
   * <p>Spring 把连接失败与读超时都包成 {@link ResourceAccessException}，必须按 cause 区分——
   * 二者的重试安全性完全相反（前者确定未执行可重试，后者结果未知不可重试）。
   */
  static boolean isReadTimeout(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof java.net.SocketTimeoutException) {
        return true;
      }
      if (t instanceof java.net.ConnectException || t instanceof java.net.UnknownHostException) {
        return false;
      }
    }
    return false;
  }

  /** 请求体：{@code ExecutionContext} 逐字段展开，`idempotencyKey` 必须传到远端供其去重。 */
  private ObjectNode requestBody(ToolManifest manifest, JsonNode args, ExecutionContext ctx) {
    ObjectNode body = mapper.createObjectNode();
    body.put("toolId", manifest.toolId());
    body.put("toolVersion", manifest.version());
    body.set("arguments", args);
    body.put("runId", ctx.runId());
    body.put("toolCallId", ctx.toolCallId());
    body.put("sessionId", ctx.sessionId());
    // 跨进程幂等的关键：provider 据此识别网络重传与 hub 重试（§3.1a.1）
    body.put("idempotencyKey", ctx.idempotencyKey());
    if (ctx.traceId() != null) {
      body.put("traceId", ctx.traceId());
    }
    return body;
  }

  /** 读超时 = Manifest 的 timeoutMs：不能更大，否则 Gateway 先超时而连接仍占用。 */
  private RestClient clientFor(String baseUrl, long timeoutMs) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS));
    factory.setReadTimeout(Duration.ofMillis(timeoutMs));
    return build(baseUrl, factory);
  }

  /** 供测试替换请求工厂。 */
  RestClient build(String baseUrl, ClientHttpRequestFactory factory) {
    return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }
}
