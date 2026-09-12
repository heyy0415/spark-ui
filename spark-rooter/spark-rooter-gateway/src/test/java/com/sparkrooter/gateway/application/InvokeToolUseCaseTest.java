package com.sparkrooter.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.model.SseEvent;
import com.sparkrooter.contracts.model.ToolInvoke;
import com.sparkrooter.gateway.domain.GatewayException;
import com.sparkrooter.gateway.domain.SessionConcurrencyLimiter;
import com.sparkrooter.gateway.infra.InMemoryIdempotencyStore;
import com.sparkrooter.gateway.infra.transport.InProcessToolTransport;
import com.sparkrooter.gateway.support.Manifests;
import com.sparkrooter.gateway.support.Providers;
import com.sparkrooter.spi.AuditSink;
import com.sparkrooter.spi.ExecutionContext;
import com.sparkrooter.spi.RunContextPropagator;
import com.sparkrooter.spi.ToolAccessPolicy;
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
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 执行面管线（agent-safety §5）：寻址 → 输入校验 → 宿主策略 → 幂等 → 调用（超时 / 重试）→ 输出校验 → 脱敏 → 审计。
 * 每个失败码一条用例；成功路径断言脱敏与审计；幂等重放断言 handler 只执行一次且审计口径为 replayed。
 */
final class InvokeToolUseCaseTest {

  /** 不限并发：这两个类测的是别的管线环节，限流单独在 SessionConcurrencyLimiterTest 测。 */
  private static final SessionConcurrencyLimiter NO_SESSION_LIMIT =
      new SessionConcurrencyLimiter(0);

  /** 不埋点：这两个类测的是别的环节，埋点单独测。 */
  private static final ToolMetricsSink NO_TOOL_METRICS = sample -> {};

  /** 与 request() 里的 ExecutionContext 保持一致；写错会让 inFlight 断言永远为 0（阶段 4 评审发现）。 */
  private static final String SESSION = "sess";

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

  // ---------------------------------------------------------------- 会话并发限制

  /**
   * 超限 → RATE_LIMITED（不是 FORBIDDEN）。
   *
   * <p>二者对调用方含义相反：限流稍后重试有意义，权限拒绝重试无意义。
   *
   * <p>必须真的把名额占住才测得到这条路径——用一个阻塞的 handler 在另一个线程里持有名额， 主线程再发起同会话调用。首版我用「上限 1 + 抛异常的 handler」写，结果把
   * RATE_LIMITED 改成 FORBIDDEN 都不会红：那个写法根本没触发限流。
   */
  @Test
  void sessionOverLimitIsRateLimited() throws Exception {
    register(Manifests.exampleJson()); // 只读（idempotency=none），不会被幂等 claim 序列化
    java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    InvokeToolUseCase gw =
        gatewayWithLimit(
            new SessionConcurrencyLimiter(1),
            handler(
                args -> {
                  holding.countDown();
                  try {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return Manifests.okOutput();
                }));

    ExecutorService caller = Executors.newSingleThreadExecutor();
    try {
      caller.submit(() -> gw.invoke(request("tc_hold", "k_hold", args("10001"))));
      assertThat(holding.await(5, java.util.concurrent.TimeUnit.SECONDS))
          .as("第一个调用应已进入 handler 并持有名额")
          .isTrue();

      // 同会话第二个调用：名额已被占满
      ToolInvoke.Response r = gw.invoke(request("tc_over", "k_over", args("10001")));

      assertThat(r.status()).isEqualTo(SseEvent.ToolStatus.failed);
      assertThat(r.error().code())
          .as("必须是 RATE_LIMITED 而非 FORBIDDEN")
          .isEqualTo(ToolInvoke.ErrorCode.RATE_LIMITED);
    } finally {
      release.countDown();
      caller.shutdownNow();
    }
  }

  /**
   * 工具抛异常后名额必须归还——否则一次失败就永久占掉该会话一个名额。
   *
   * <p>这是 `finally` 释放的核心断言：连续 10 次失败调用后仍能成功调用。
   */
  @Test
  void leaseIsReleasedEvenWhenToolFails() {
    register(Manifests.exampleJson());
    SessionConcurrencyLimiter limiter = new SessionConcurrencyLimiter(1);
    AtomicInteger calls = new AtomicInteger();
    InvokeToolUseCase failing =
        gatewayWithLimit(
            limiter,
            handler(
                args -> {
                  calls.incrementAndGet();
                  throw new IllegalStateException("boom");
                }));

    for (int i = 0; i < 10; i++) {
      failing.invoke(request("tc_f" + i, "k_f" + i, args("10001")));
    }

    assertThat(limiter.inFlight(SESSION)).as("失败路径也必须归还名额").isZero();
    assertThat(limiter.trackedSessions()).as("归零后不留 map 项").isZero();

    // 名额确实可再用：换一个成功的 gateway 仍能跑通
    InvokeToolUseCase ok = gatewayWithLimit(limiter, handler(args -> Manifests.okOutput()));
    assertThat(ok.invoke(request("tc_ok", "k_ok", args("10001"))).status())
        .isEqualTo(SseEvent.ToolStatus.succeeded);
  }

  /** 默认配置（不限制）下行为与本 change 之前完全一致。 */
  @Test
  void disabledLimiterDoesNotChangeBehaviour() {
    register(Manifests.exampleJson());
    InvokeToolUseCase gw =
        gatewayWithLimit(new SessionConcurrencyLimiter(0), handler(args -> Manifests.okOutput()));
    for (int i = 0; i < 20; i++) {
      assertThat(gw.invoke(request("tc_d" + i, "k_d" + i, args("10001"))).status())
          .isEqualTo(SseEvent.ToolStatus.succeeded);
    }
  }

  // ---------------------------------------------------------------- 审计失败不吞结果

  /**
   * AuditSink 抛异常时，**已执行的工具结果照常返回**。
   *
   * <p>阶段 2 评审 M-3 发现的既有缺陷：`audit.record` 夹在「工具已执行完」与「return 结果」之间。 宿主把 AuditSink 换成写库 / 写 Kafka
   * 并抛异常时，副作用已发生但调用方收到失败 → 用户看到 「请稍后重试」→ 可能真的重试 → **重复扣款**。
   */
  @Test
  void auditFailureDoesNotSwallowSuccessfulResult() {
    register(Manifests.exampleJson());
    InvokeToolUseCase gw =
        gatewayWithAudit(
            entry -> {
              throw new IllegalStateException("audit backend down");
            },
            handler(args -> Manifests.okOutput()));

    ToolInvoke.Response r = gw.invoke(request("tc_a1", "k_a1", args("10001")));

    assertThat(r.status()).as("工具已执行成功，审计失败不该让调用方看到失败").isEqualTo(SseEvent.ToolStatus.succeeded);
  }

  /**
   * 失败路径下审计抛异常，原本的 GatewayException 不被掩盖。
   *
   * <p>否则「输入不合法」会变成「审计后端挂了」，排查方向完全错。
   */
  @Test
  void auditFailureDoesNotMaskTheOriginalError() {
    register(Manifests.exampleJson());
    InvokeToolUseCase gw =
        gatewayWithAudit(
            entry -> {
              throw new IllegalStateException("audit backend down");
            },
            handler(args -> Manifests.okOutput()));

    ObjectNode bad = Manifests.VALIDATOR.mapper().createObjectNode().put("orderId", "");
    ToolInvoke.Response r = gw.invoke(request("tc_a2", "k_a2", bad));

    assertThat(r.error().code())
        .as("原错误码必须保留，不能被审计异常替换")
        .isEqualTo(ToolInvoke.ErrorCode.INPUT_INVALID);
  }

  // ---------------------------------------------------------------- 埋点

  /**
   * 埋点实现抛异常时，工具结果照常返回（评审 S-2）。
   *
   * <p>监控系统拖垮业务是经典事故：宿主的 MeterRegistry 可能因标签冲突、后端不可达而抛异常。
   */
  @Test
  void metricsFailureDoesNotSwallowSuccessfulResult() {
    register(Manifests.exampleJson());
    InvokeToolUseCase gw =
        gatewayWithMetrics(
            sample -> {
              throw new IllegalStateException("registry down");
            },
            handler(args -> Manifests.okOutput()));

    ToolInvoke.Response r = gw.invoke(request("tc_m1", "k_m1", args("10001")));

    assertThat(r.status()).as("监控故障不得让已执行的工具对调用方表现为失败").isEqualTo(SseEvent.ToolStatus.succeeded);
  }

  /** 埋点样本不含高基数标识（sessionId / runId / toolCallId）。 */
  @Test
  void metricsSampleCarriesNoHighCardinalityIds() {
    register(Manifests.exampleJson());
    List<ToolMetricsSink.Sample> captured = new ArrayList<>();
    InvokeToolUseCase gw = gatewayWithMetrics(captured::add, handler(args -> Manifests.okOutput()));

    gw.invoke(request("tc_m2", "k_m2", args("10001")));

    assertThat(captured).hasSize(1);
    String asText = captured.get(0).toString();
    assertThat(asText)
        .as("高基数标识会打爆时序库，不得进指标")
        .doesNotContain("tc_m2")
        .doesNotContain(SESSION)
        .doesNotContain("run_1");
    assertThat(captured.get(0).toolId()).isEqualTo(TOOL);
    assertThat(captured.get(0).status()).isEqualTo("succeeded");
  }

  /** 失败路径也埋点，且带错误码（否则错误率指标看不出失败原因）。 */
  @Test
  void failedInvocationIsAlsoMetered() {
    List<ToolMetricsSink.Sample> captured = new ArrayList<>();
    InvokeToolUseCase gw = gatewayWithMetrics(captured::add, handler(args -> Manifests.okOutput()));

    gw.invoke(request("tc_m3", "k_m3", args("10001"))); // 未注册 → TOOL_NOT_FOUND

    assertThat(captured).hasSize(1);
    assertThat(captured.get(0).status()).isEqualTo("failed");
    assertThat(captured.get(0).errorCode()).isEqualTo("TOOL_NOT_FOUND");
  }

  /**
   * 阶段 4 评审 F-1：等待幂等结果的调用**不应长期占用会话名额**。
   *
   * <p>场景：同 idempotencyKey 的并发调用里，只有 1 个是 owner 在真执行，其余在 claimOrAwait 里阻塞等待（最长
   * timeoutMs）。若它们都占着名额，该会话的其他正常查询会被误拒—— 名额本该衡量「真正占资源的并发」，等待不占 CPU 也不占工具。
   */
  @Test
  void waitersOnIdempotentKeyDoNotExhaustSessionQuota() throws Exception {
    // sideEffect=true + idempotency=required：唯一会走 claimOrAwait 的组合
    ObjectNode j = Manifests.jsonWithExecution(3000, 0, "required");
    ((ObjectNode) j.get("risk")).put("sideEffect", true);
    register(j);

    java.util.concurrent.CountDownLatch inHandler = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    SessionConcurrencyLimiter limiter = new SessionConcurrencyLimiter(2);
    InvokeToolUseCase gw =
        gatewayWithLimit(
            limiter,
            handler(
                args -> {
                  inHandler.countDown();
                  try {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return Manifests.okOutput();
                }));

    ExecutorService callers = Executors.newFixedThreadPool(2);
    try {
      // owner：占 1 个名额并卡在 handler 里
      callers.submit(() -> gw.invoke(request("tc_own", "same-key", args("10001"))));
      assertThat(inHandler.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

      // waiter：同 key，会进 claimOrAwait 等待，占掉第 2 个名额
      callers.submit(() -> gw.invoke(request("tc_wait", "same-key", args("10001"))));
      Thread.sleep(200); // 让 waiter 进入等待

      assertThat(limiter.inFlight(SESSION))
          .as("owner + waiter 共占 2 个名额——名额算在飞请求，不算在跑的工具（评审 F-1 的决策）")
          .isEqualTo(2);
    } finally {
      release.countDown();
      callers.shutdownNow();
    }
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
        List.of(transport),
        NO_SESSION_LIMIT,
        NO_TOOL_METRICS);
  }

  /** 指定 ToolMetricsSink 的 gateway。 */
  private InvokeToolUseCase gatewayWithMetrics(ToolMetricsSink sink, ToolHandler... handlers) {
    InProcessToolTransport transport = new InProcessToolTransport();
    for (ToolHandler h : handlers) {
      transport.register(h);
    }
    return new InvokeToolUseCase(
        resolver,
        Providers.of(null),
        propagator,
        idempotency,
        audit,
        Manifests.VALIDATOR,
        executor,
        List.of(transport),
        NO_SESSION_LIMIT,
        sink);
  }

  /** 指定 AuditSink 的 gateway（用于测审计失败路径）。 */
  private InvokeToolUseCase gatewayWithAudit(AuditSink sink, ToolHandler... handlers) {
    InProcessToolTransport transport = new InProcessToolTransport();
    for (ToolHandler h : handlers) {
      transport.register(h);
    }
    return new InvokeToolUseCase(
        resolver,
        Providers.of(null),
        propagator,
        idempotency,
        sink,
        Manifests.VALIDATOR,
        executor,
        List.of(transport),
        NO_SESSION_LIMIT,
        NO_TOOL_METRICS);
  }

  /** 指定限流器的 gateway（默认 helper 用 NO_SESSION_LIMIT）。 */
  private InvokeToolUseCase gatewayWithLimit(
      SessionConcurrencyLimiter limiter, ToolHandler... handlers) {
    InProcessToolTransport transport = new InProcessToolTransport();
    for (ToolHandler h : handlers) {
      transport.register(h);
    }
    return new InvokeToolUseCase(
        resolver,
        Providers.of(null),
        propagator,
        idempotency,
        audit,
        Manifests.VALIDATOR,
        executor,
        List.of(transport),
        limiter,
        NO_TOOL_METRICS);
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
        new ToolInvoke.ExecutionContext("run_t", toolCallId, SESSION, idemKey, "trace"));
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
