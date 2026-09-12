package com.sparkrooter.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.model.SseEvent;
import com.sparkrooter.contracts.model.ToolInvoke;
import com.sparkrooter.gateway.domain.GatewayException;
import com.sparkrooter.gateway.infra.InMemoryIdempotencyStore;
import com.sparkrooter.gateway.infra.transport.InProcessToolTransport;
import com.sparkrooter.gateway.support.Manifests;
import com.sparkrooter.gateway.support.Providers;
import com.sparkrooter.spi.AuditSink;
import com.sparkrooter.spi.ExecutionContext;
import com.sparkrooter.spi.RunContextPropagator;
import com.sparkrooter.spi.ToolAccessPolicy;
import com.sparkrooter.spi.ToolHandler;
import com.sparkrooter.spi.ToolResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 执行面管线（agent-safety §5）：寻址 → 输入校验 → 宿主策略 → 幂等 → 调用（超时 / 重试）→ 输出校验 → 脱敏 → 审计。
 * 每个失败码一条用例；成功路径断言脱敏与审计；幂等重放断言 handler 只执行一次且审计口径为 replayed。
 */
final class InvokeToolUseCaseTest {

  private static final String TOOL = "refund.eligibility.check";
  private static final String VERSION = "1.2.0";

  private final Map<String, JsonNode> registry = new ConcurrentHashMap<>();
  private final ToolResolver resolver =
      (toolId, version) -> Optional.ofNullable(registry.get(toolId + "@" + version));
  private final List<AuditSink.Entry> audits = new ArrayList<>();
  private final AuditSink audit = audits::add;
  private final RecordingPropagator propagator = new RecordingPropagator();
  private final InMemoryIdempotencyStore idempotency = new InMemoryIdempotencyStore();
  private final ExecutorService executor = Executors.newFixedThreadPool(2);

  @AfterEach
  void shutdown() {
    executor.shutdownNow();
  }

  // ---------------------------------------------------------------- 失败码

  @Test
  void unknownToolIsToolNotFound() {
    InvokeToolUseCase gw = gateway(null, handler(args -> Manifests.okOutput()));
    ToolInvoke.Response r = gw.invoke(request("tc_1", "k1", args("10001")));
    assertThat(r.status()).isEqualTo(SseEvent.ToolStatus.failed);
    assertThat(r.error().code()).isEqualTo(ToolInvoke.ErrorCode.TOOL_NOT_FOUND);
    assertThat(audits)
        .singleElement()
        .extracting(AuditSink.Entry::status)
        .isEqualTo("failed:TOOL_NOT_FOUND");
  }

  @Test
  void argumentsViolatingInputSchemaAreInputInvalid() {
    register(Manifests.exampleJson());
    InvokeToolUseCase gw = gateway(null, handler(args -> Manifests.okOutput()));
    ObjectNode bad = Manifests.VALIDATOR.mapper().createObjectNode().put("orderId", "");
    ToolInvoke.Response r = gw.invoke(request("tc_2", "k2", bad));
    assertThat(r.error().code()).isEqualTo(ToolInvoke.ErrorCode.INPUT_INVALID);
  }

  @Test
  void hostPolicyDenialIsForbidden() {
    register(Manifests.exampleJson());
    AtomicInteger calls = new AtomicInteger();
    ToolAccessPolicy deny = (toolId, sessionId) -> false;
    InvokeToolUseCase gw =
        gateway(
            deny,
            handler(
                args -> {
                  calls.incrementAndGet();
                  return Manifests.okOutput();
                }));
    ToolInvoke.Response r = gw.invoke(request("tc_3", "k3", args("10001")));
    assertThat(r.error().code()).isEqualTo(ToolInvoke.ErrorCode.FORBIDDEN);
    assertThat(calls).hasValue(0);
  }

  @Test
  void registeredManifestWithoutHandlerIsToolNotFound() {
    register(Manifests.exampleJson());
    InvokeToolUseCase gw = gateway(null); // 无任何 handler
    ToolInvoke.Response r = gw.invoke(request("tc_4", "k4", args("10001")));
    assertThat(r.error().code()).isEqualTo(ToolInvoke.ErrorCode.TOOL_NOT_FOUND);
    assertThat(r.error().message()).contains("no handler bound");
  }

  @Test
  void handlerExceptionIsHandlerErrorAndReadOnlyToolRetries() {
    // 示例 Manifest：sideEffect=false, maxRetries=1 → 允许重试 1 次，共执行 2 次
    register(Manifests.exampleJson());
    AtomicInteger calls = new AtomicInteger();
    InvokeToolUseCase gw =
        gateway(
            null,
            handler(
                args -> {
                  calls.incrementAndGet();
                  throw new IllegalStateException("boom");
                }));
    ToolInvoke.Response r = gw.invoke(request("tc_5", "k5", args("10001")));
    assertThat(r.error().code()).isEqualTo(ToolInvoke.ErrorCode.HANDLER_ERROR);
    assertThat(r.error().message()).contains("boom");
    assertThat(calls).hasValue(2);
  }

  @Test
  void idempotentSideEffectToolRetriesUpToMaxRetries() {
    // sideEffect=true 且 idempotency=required（契约允许的唯一有副作用组合）：maxRetries=3 → 共执行 4 次
    ObjectNode j = Manifests.jsonWithExecution(3000, 3, "required");
    ((ObjectNode) j.get("risk")).put("sideEffect", true);
    register(j);
    AtomicInteger calls = new AtomicInteger();
    InvokeToolUseCase gw =
        gateway(
            null,
            handler(
                args -> {
                  calls.incrementAndGet();
                  throw new IllegalStateException("boom");
                }));
    gw.invoke(request("tc_6", "k6", args("10001")));
    assertThat(calls).hasValue(4);
  }

  @Test
  void slowHandlerIsTimeout() {
    register(Manifests.jsonWithExecution(100, 0, "none"));
    InvokeToolUseCase gw =
        gateway(
            null,
            handler(
                args -> {
                  try {
                    Thread.sleep(300);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return Manifests.okOutput();
                }));
    ToolInvoke.Response r = gw.invoke(request("tc_7", "k7", args("10001")));
    assertThat(r.error().code()).isEqualTo(ToolInvoke.ErrorCode.TIMEOUT);
  }

  @Test
  void outputViolatingOutputSchemaIsOutputInvalid() {
    register(Manifests.exampleJson());
    InvokeToolUseCase gw =
        gateway(
            null,
            handler(
                args -> Manifests.VALIDATOR.mapper().createObjectNode().put("eligible", "yes")));
    ToolInvoke.Response r = gw.invoke(request("tc_8", "k8", args("10001")));
    assertThat(r.error().code()).isEqualTo(ToolInvoke.ErrorCode.OUTPUT_INVALID);
  }

  @Test
  void executeThrowsGatewayExceptionWhileInvokeReturnsResponse() {
    InvokeToolUseCase gw = gateway(null);
    ToolInvoke.Request req = request("tc_9", "k9", args("10001"));
    assertThatThrownBy(() -> gw.execute(req))
        .isInstanceOf(GatewayException.class)
        .satisfies(
            e ->
                assertThat(((GatewayException) e).code())
                    .isEqualTo(ToolInvoke.ErrorCode.TOOL_NOT_FOUND));
    assertThat(gw.invoke(req).status()).isEqualTo(SseEvent.ToolStatus.failed);
  }

  // ---------------------------------------------------------------- 成功路径

  @Test
  void successRedactsSensitiveKeysAndAuditsOnce() {
    // outputSchema additionalProperties=false：把敏感键放进 reason 无法测脱敏，这里放宽 outputSchema
    ObjectNode j = Manifests.exampleJson();
    ((ObjectNode) j.get("outputSchema")).put("additionalProperties", true);
    register(j);
    InvokeToolUseCase gw =
        gateway(
            null,
            handler(
                args -> {
                  ObjectNode out = Manifests.okOutput();
                  out.put("token", "secret-value");
                  out.putObject("nested").put("apiKey", "k").put("keep", "v");
                  out.putArray("list").addObject().put("password", "p");
                  return out;
                }));
    ToolInvoke.Response r = gw.invoke(request("tc_10", "k10", args("10001")));

    assertThat(r.status()).isEqualTo(SseEvent.ToolStatus.succeeded);
    assertThat(r.output().path("token").asText()).isEqualTo("***");
    assertThat(r.output().path("nested").path("apiKey").asText()).isEqualTo("***");
    assertThat(r.output().path("nested").path("keep").asText()).isEqualTo("v");
    assertThat(r.output().path("list").get(0).path("password").asText()).isEqualTo("***");
    assertThat(r.output().path("eligible").asBoolean()).isTrue();

    assertThat(audits).hasSize(1);
    AuditSink.Entry a = audits.get(0);
    assertThat(a.status()).isEqualTo("succeeded");
    assertThat(a.toolId()).isEqualTo(TOOL);
    assertThat(a.sessionId()).isEqualTo("sess");
    assertThat(a.argsDigest()).hasSize(32);
    assertThat(a.traceId()).isEqualTo("trace");
  }

  @Test
  void hostContextIsPropagatedToToolThread() {
    register(Manifests.exampleJson());
    List<String> seenOnToolThread = new ArrayList<>();
    InvokeToolUseCase gw =
        gateway(
            null,
            handler(
                args -> {
                  seenOnToolThread.add(RecordingPropagator.CURRENT.get());
                  return Manifests.okOutput();
                }));
    RecordingPropagator.CURRENT.set("user-42");
    try {
      gw.invoke(request("tc_11", "k11", args("10001")));
    } finally {
      RecordingPropagator.CURRENT.remove();
    }
    assertThat(seenOnToolThread).containsExactly("user-42");
    assertThat(propagator.cleared).isEqualTo(1);
  }

  // ---------------------------------------------------------------- 幂等

  @Test
  void idempotentReplayExecutesHandlerOnceAndAuditsReplayed() {
    register(Manifests.jsonWithExecution(3000, 0, "required"));
    AtomicInteger calls = new AtomicInteger();
    InvokeToolUseCase gw =
        gateway(
            null,
            handler(
                args -> {
                  calls.incrementAndGet();
                  return Manifests.okOutput();
                }));
    ToolInvoke.Response first = gw.invoke(request("tc_12a", "same-key", args("10001")));
    ToolInvoke.Response second = gw.invoke(request("tc_12b", "same-key", args("10001")));

    assertThat(calls).hasValue(1);
    assertThat(first.status()).isEqualTo(SseEvent.ToolStatus.succeeded);
    assertThat(second.toolCallId()).isEqualTo(first.toolCallId());
    assertThat(audits).extracting(AuditSink.Entry::status).containsExactly("succeeded", "replayed");
  }

  @Test
  void failedIdempotentOwnerReleasesClaimSoRetryCanRun() {
    register(Manifests.jsonWithExecution(3000, 0, "required"));
    AtomicInteger calls = new AtomicInteger();
    InvokeToolUseCase gw =
        gateway(
            null,
            handler(
                args -> {
                  if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("first fails");
                  }
                  return Manifests.okOutput();
                }));
    assertThat(gw.invoke(request("tc_13a", "k13", args("10001"))).status())
        .isEqualTo(SseEvent.ToolStatus.failed);
    assertThat(gw.invoke(request("tc_13b", "k13", args("10001"))).status())
        .isEqualTo(SseEvent.ToolStatus.succeeded);
    assertThat(calls).hasValue(2);
  }

  @Test
  void nonIdempotentToolDoesNotDedupe() {
    register(Manifests.exampleJson()); // idempotency=none
    AtomicInteger calls = new AtomicInteger();
    InvokeToolUseCase gw =
        gateway(
            null,
            handler(
                args -> {
                  calls.incrementAndGet();
                  return Manifests.okOutput();
                }));
    gw.invoke(request("tc_14a", "dup", args("10001")));
    gw.invoke(request("tc_14b", "dup", args("10001")));
    assertThat(calls).hasValue(2);
  }

  @Test
  void duplicateHandlerRegistrationFailsFast() {
    // handler 注册表在 InProcessToolTransport（T04 起）：重复注册仍启动期失败，只是守卫点下移
    ToolHandler h = handler(args -> Manifests.okOutput());
    assertThatThrownBy(() -> gateway(null, h, h))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate tool handler");

    InProcessToolTransport transport = new InProcessToolTransport();
    transport.register(h);
    assertThatThrownBy(() -> transport.register(h))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate tool handler");
  }

  // ---------------------------------------------------------------- helpers

  private void register(ObjectNode manifestJson) {
    registry.put(
        manifestJson.path("toolId").asText() + "@" + manifestJson.path("version").asText(),
        manifestJson);
  }

  private InvokeToolUseCase gateway(ToolAccessPolicy policy, ToolHandler... handlers) {
    InProcessToolTransport transport = new InProcessToolTransport();
    for (ToolHandler h : handlers) {
      transport.register(h);
    }
    return new InvokeToolUseCase(
        resolver,
        Providers.of(policy),
        propagator,
        idempotency,
        audit,
        Manifests.VALIDATOR,
        executor,
        List.of(transport));
  }

  private static ToolHandler handler(Function<JsonNode, JsonNode> fn) {
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
        return fn.apply(args);
      }
    };
  }

  private static ObjectNode args(String orderId) {
    return Manifests.VALIDATOR.mapper().createObjectNode().put("orderId", orderId);
  }

  private static ToolInvoke.Request request(String toolCallId, String idemKey, JsonNode args) {
    return new ToolInvoke.Request(
        TOOL,
        VERSION,
        args,
        new ToolInvoke.ExecutionContext("run_t", toolCallId, "sess", idemKey, "trace"));
  }

  /** 记录型传播器：capture 读当前线程的值，restore 写到工具线程，clear 计数。 */
  static final class RecordingPropagator implements RunContextPropagator {
    static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    int cleared;

    @Override
    public Object capture() {
      return CURRENT.get();
    }

    @Override
    public void restore(Object captured) {
      CURRENT.set((String) captured);
    }

    @Override
    public void clear() {
      CURRENT.remove();
      cleared++;
    }
  }
}
