package com.sparkrooter.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.model.SseEvent;
import com.sparkrooter.contracts.model.ToolInvoke;
import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.contracts.tool.ToolTransport;
import com.sparkrooter.contracts.tool.ToolTransportException;
import com.sparkrooter.gateway.domain.SessionConcurrencyLimiter;
import com.sparkrooter.gateway.infra.InMemoryIdempotencyStore;
import com.sparkrooter.gateway.infra.transport.InProcessToolTransport;
import com.sparkrooter.gateway.support.Manifests;
import com.sparkrooter.gateway.support.Providers;
import com.sparkrooter.spi.AuditSink;
import com.sparkrooter.spi.ExecutionContext;
import com.sparkrooter.spi.RunContextPropagator;
import com.sparkrooter.spi.ToolHandler;
import com.sparkrooter.spi.ToolMetricsSink;
import com.sparkrooter.spi.ToolResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * transport 分派（T04 引入）：Gateway 按 {@code manifest.protocol()} 选传输，治理全部留在 Gateway。
 *
 * <p>本类守两件事：
 *
 * <ol>
 *   <li><b>治理顺序未因分派而改变</b>——分派点在「幂等之后、输出校验之前」，前后各环节照原样执行；
 *   <li><b>跨进程重试语义</b>——传输层声明「结果未知」时不得重试（否则远端可能重复扣款）。
 * </ol>
 */
final class ToolTransportDispatchTest {

  /** 不限并发：这两个类测的是别的管线环节，限流单独在 SessionConcurrencyLimiterTest 测。 */
  private static final SessionConcurrencyLimiter NO_SESSION_LIMIT =
      new SessionConcurrencyLimiter(0);

  /** 不埋点：这两个类测的是别的环节，埋点单独测。 */
  private static final ToolMetricsSink NO_TOOL_METRICS = sample -> {};

  private static final String TOOL = "refund.eligibility.check";
  private static final String VERSION = "1.2.0";

  private final Map<String, JsonNode> registry = new ConcurrentHashMap<>();
  private final ToolResolver resolver =
      (toolId, version) -> Optional.ofNullable(registry.get(toolId + "@" + version));
  private final List<AuditSink.Entry> audits = new ArrayList<>();
  private final AuditSink audit = audits::add;
  private final InMemoryIdempotencyStore idempotency = new InMemoryIdempotencyStore();
  private final ExecutorService executor = Executors.newFixedThreadPool(2);

  @AfterEach
  void shutdown() {
    executor.shutdownNow();
  }

  // ------------------------------------------------------------ 分派

  /** 只有 in-process 传输时，in-process 的 Manifest 正常执行。 */
  @Test
  void inProcessManifestGoesToInProcessTransport() {
    register(Manifests.exampleJson());
    AtomicInteger calls = new AtomicInteger();
    InProcessToolTransport local = new InProcessToolTransport();
    local.register(handler(args -> Manifests.okOutput(), calls));

    ToolInvoke.Response r = gateway(List.of(local)).invoke(request("tc_1", "k1", args("10001")));

    assertThat(r.status()).isEqualTo(SseEvent.ToolStatus.succeeded);
    assertThat(calls).hasValue(1);
  }

  /**
   * Manifest 声明的协议没有对应传输 → TOOL_NOT_FOUND 而非静默走本地。
   *
   * <p>这是最危险的误实现：若缺省回落到 in-process，远程工具会被"就近"执行成另一个同名本地工具。
   */
  @Test
  void manifestWithUnassembledProtocolIsToolNotFound() {
    ObjectNode j = Manifests.exampleJson();
    j.put("protocol", "http");
    j.set(
        "provider",
        Manifests.VALIDATOR
            .mapper()
            .createObjectNode()
            .put("serviceName", "refund-service")
            .put("baseUrl", "http://refund-service.internal:8080"));
    register(j);
    AtomicInteger localCalls = new AtomicInteger();
    InProcessToolTransport local = new InProcessToolTransport();
    local.register(handler(args -> Manifests.okOutput(), localCalls));

    ToolInvoke.Response r = gateway(List.of(local)).invoke(request("tc_2", "k2", args("10001")));

    assertThat(r.error().code()).isEqualTo(ToolInvoke.ErrorCode.TOOL_NOT_FOUND);
    assertThat(r.error().message()).contains("no transport for protocol http");
    // 关键：绝不能因为本地有同名 handler 就执行它
    assertThat(localCalls).hasValue(0);
  }

  /** 装配两个同协议传输 → 启动期失败，不留到运行时才发现分派歧义。 */
  @Test
  void duplicateProtocolTransportFailsFast() {
    assertThatThrownBy(
            () -> gateway(List.of(new InProcessToolTransport(), new InProcessToolTransport())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate ToolTransport");
  }

  /** 一个传输都没装配 → 启动期失败（任何工具都调不通）。 */
  @Test
  void noTransportAtAllFailsFast() {
    assertThatThrownBy(() -> gateway(List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ToolTransport registered");
  }

  // ------------------------------------------------------------ 治理仍在 Gateway

  /**
   * 治理顺序未因分派而改变：输入校验在传输**之前**（非法参数不该到达工具），输出校验与脱敏在**之后**。
   *
   * <p>用一个自定义传输观测「有没有被调到」，比断言日志更直接。
   */
  @Test
  void inputValidationStillRunsBeforeTransport() {
    register(Manifests.exampleJson());
    AtomicInteger reached = new AtomicInteger();
    ToolTransport spy =
        transport(
            ToolManifest.Protocol.IN_PROCESS,
            (m, a) -> {
              reached.incrementAndGet();
              return Manifests.okOutput();
            });

    ObjectNode bad = Manifests.VALIDATOR.mapper().createObjectNode().put("orderId", "");
    ToolInvoke.Response r = gateway(List.of(spy)).invoke(request("tc_3", "k3", bad));

    assertThat(r.error().code()).isEqualTo(ToolInvoke.ErrorCode.INPUT_INVALID);
    assertThat(reached).as("输入校验必须在传输之前").hasValue(0);
  }

  /** 输出校验仍在传输之后：传输返回不合 outputSchema 的结果 → OUTPUT_INVALID。 */
  @Test
  void outputValidationStillRunsAfterTransport() {
    register(Manifests.exampleJson());
    ToolTransport bad =
        transport(
            ToolManifest.Protocol.IN_PROCESS,
            (m, a) -> Manifests.VALIDATOR.mapper().createObjectNode().put("eligible", "yes"));

    ToolInvoke.Response r = gateway(List.of(bad)).invoke(request("tc_4", "k4", args("10001")));

    assertThat(r.error().code()).isEqualTo(ToolInvoke.ErrorCode.OUTPUT_INVALID);
  }

  /** 审计仍在 Gateway：无论走哪个传输都留痕。 */
  @Test
  void auditStillRecordedForTransportPath() {
    register(Manifests.exampleJson());
    ToolTransport ok = transport(ToolManifest.Protocol.IN_PROCESS, (m, a) -> Manifests.okOutput());

    gateway(List.of(ok)).invoke(request("tc_5", "k5", args("10001")));

    assertThat(audits).singleElement().extracting(AuditSink.Entry::status).isEqualTo("succeeded");
  }

  // ------------------------------------------------------------ 跨进程重试语义

  /**
   * 传输层声明「结果未知」（远端超时）→ <b>不重试</b>。
   *
   * <p>这是 M-1 的核心断言。示例 Manifest 是 {@code sideEffect=false, maxRetries=1}，进程内语义下会重试一次；
   * 但传输层说不可重试时必须立即停止 —— 远端可能已执行成功，重试会造成重复副作用。
   */
  @Test
  void remoteTimeoutIsNotRetried() {
    register(Manifests.exampleJson());
    AtomicInteger attempts = new AtomicInteger();
    ToolTransport timingOut =
        transport(
            ToolManifest.Protocol.IN_PROCESS,
            (m, a) -> {
              attempts.incrementAndGet();
              throw new ToolTransportException(
                  ToolTransportException.Kind.REMOTE_TIMEOUT, "read timed out");
            });

    ToolInvoke.Response r =
        gateway(List.of(timingOut)).invoke(request("tc_6", "k6", args("10001")));

    assertThat(r.error().code()).isEqualTo(ToolInvoke.ErrorCode.TIMEOUT);
    assertThat(attempts).as("结果未知时不得重试").hasValue(1);
  }

  /** 远端已收到并返回失败 → 同样不重试（已执行过，是否生效未知）。 */
  @Test
  void remoteFailureIsNotRetried() {
    register(Manifests.exampleJson());
    AtomicInteger attempts = new AtomicInteger();
    ToolTransport failing =
        transport(
            ToolManifest.Protocol.IN_PROCESS,
            (m, a) -> {
              attempts.incrementAndGet();
              throw new ToolTransportException(
                  ToolTransportException.Kind.REMOTE_FAILED, "provider returned 500");
            });

    ToolInvoke.Response r = gateway(List.of(failing)).invoke(request("tc_7", "k7", args("10001")));

    assertThat(r.error().code()).isEqualTo(ToolInvoke.ErrorCode.HANDLER_ERROR);
    // 远端错误详情不原样透给用户
    assertThat(r.error().message()).doesNotContain("500").contains("REMOTE_FAILED");
    assertThat(attempts).hasValue(1);
  }

  /**
   * 确定未到达工具（连接被拒）→ <b>照常重试</b>。
   *
   * <p>与上两条对照：重试安全的判据是「这次调用到底有没有碰到工具」，不是「错误严不严重」。
   */
  @Test
  void unreachableProviderIsRetried() {
    register(Manifests.exampleJson()); // maxRetries=1 → 共 2 次
    AtomicInteger attempts = new AtomicInteger();
    ToolTransport unreachable =
        transport(
            ToolManifest.Protocol.IN_PROCESS,
            (m, a) -> {
              attempts.incrementAndGet();
              throw new ToolTransportException(
                  ToolTransportException.Kind.UNREACHABLE, "connection refused");
            });

    gateway(List.of(unreachable)).invoke(request("tc_8", "k8", args("10001")));

    assertThat(attempts).as("确定未到达工具时可安全重试").hasValue(2);
  }

  /** 传输层报找不到工具 → TOOL_NOT_FOUND，且可重试（可能是注册尚未同步）。 */
  @Test
  void transportNotFoundMapsToToolNotFound() {
    register(Manifests.exampleJson());
    ToolTransport empty = new InProcessToolTransport(); // 未注册任何 handler

    ToolInvoke.Response r = gateway(List.of(empty)).invoke(request("tc_9", "k9", args("10001")));

    assertThat(r.error().code()).isEqualTo(ToolInvoke.ErrorCode.TOOL_NOT_FOUND);
    assertThat(r.error().message()).contains("no handler bound");
  }

  // ------------------------------------------------------------ helpers

  private void register(ObjectNode manifestJson) {
    registry.put(
        manifestJson.path("toolId").asText() + "@" + manifestJson.path("version").asText(),
        manifestJson);
  }

  private InvokeToolUseCase gateway(List<ToolTransport> transports) {
    return new InvokeToolUseCase(
        resolver,
        Providers.of(null),
        noopPropagator(),
        idempotency,
        audit,
        Manifests.VALIDATOR,
        executor,
        transports,
        NO_SESSION_LIMIT,
        NO_TOOL_METRICS);
  }

  /** 按协议返回一个行为可编程的传输，用于观测 Gateway 的调用时机。 */
  private static ToolTransport transport(
      ToolManifest.Protocol protocol, BiFunction<ToolManifest, JsonNode, JsonNode> fn) {
    return new ToolTransport() {
      @Override
      public ToolManifest.Protocol protocol() {
        return protocol;
      }

      @Override
      public JsonNode invoke(ToolManifest manifest, JsonNode args, ExecutionContext ctx) {
        return fn.apply(manifest, args);
      }
    };
  }

  private static ToolHandler handler(
      java.util.function.Function<JsonNode, JsonNode> fn, AtomicInteger calls) {
    return new ToolHandler() {
      @Override
      public String toolId() {
        return TOOL;
      }

      @Override
      public String version() {
        return VERSION;
      }

      @Override
      public JsonNode handle(JsonNode args, ExecutionContext ctx) {
        calls.incrementAndGet();
        return fn.apply(args);
      }
    };
  }

  private static RunContextPropagator noopPropagator() {
    return new RunContextPropagator() {
      @Override
      public Object capture() {
        return null;
      }

      @Override
      public void restore(Object captured) {}

      @Override
      public void clear() {}
    };
  }

  private static ObjectNode args(String orderId) {
    return Manifests.VALIDATOR.mapper().createObjectNode().put("orderId", orderId);
  }

  private static ToolInvoke.Request request(String toolCallId, String key, JsonNode arguments) {
    return new ToolInvoke.Request(
        TOOL,
        VERSION,
        arguments,
        new ToolInvoke.ExecutionContext("run_1", toolCallId, "sess_1", key, null));
  }
}
