package com.strato.runtime.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.strato.contracts.SchemaValidator;
import com.strato.contracts.model.IntentRequest;
import com.strato.contracts.model.RunFailureCode;
import com.strato.contracts.model.SseEvent;
import com.strato.contracts.model.ToolInvoke;
import com.strato.contracts.model.ToolSearch;
import com.strato.contracts.model.UiSchema;
import com.strato.runtime.application.port.LlmClient;
import com.strato.runtime.application.port.RunEventSink;
import com.strato.runtime.application.port.ToolGatewayClient;
import com.strato.runtime.application.port.ToolRegistryClient;
import com.strato.runtime.application.screen.ScreenRegistry;
import com.strato.runtime.domain.ConfirmationToken;
import com.strato.runtime.domain.Plan;
import com.strato.runtime.domain.Run;
import com.strato.runtime.domain.RunFailure;
import com.strato.runtime.domain.RunRepository;
import com.strato.runtime.domain.RunState;
import com.strato.runtime.domain.Step;
import com.strato.spi.ConfirmationRecheck;
import com.strato.spi.Principal;
import com.strato.spi.ScreenContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Run 编排器（决策面 / 状态面）。 意图 → 领域路由 → Registry 搜索 → LLM 规划 → 逐步执行：低风险经 Gateway 自动执行；遇
 * requiresConfirmation 生成 UI Schema + Token 并进入 WAITING_CONFIRMATION；确认后先经 Gateway 重校验，再执行目标工具。
 * 任何工具调用都经 ToolGatewayClient（agent-safety §1）。事件按 spec §4.0 发射，并在发出前经 sse-events 契约校验。
 *
 * <p>屏与领域策略都不在这里：确认屏 / 结果屏查 ScreenRegistry（领域 ScreenBuilder），确认后重校验查 RecheckRegistry（领域
 * ConfirmationRecheck）；需确认工具缺任一者 → fail-closed INTERNAL_ERROR。
 *
 * <p>确认路径按 runId 互斥：并发 / 重放的确认请求要么等待，要么因令牌已消费被拒绝，且这类前置拒绝不改变 Run 状态， 保证成功执行的写操作不会被并发请求报告为
 * FAILED。可信参数（如退款金额）一律取重校验结果并与确认屏展示值比对，模型或前端都无法决定。
 */
@Service
public class RunOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(RunOrchestrator.class);
  private static final String NO_CAPABILITY_TEXT = "当前没有可用能力处理该请求";

  /** 领域重校验策略拒绝时的用户文案（区分于令牌 / 并发拒绝）。 */
  private static final String POLICY_REJECTED_TEXT = "订单状态已变化，本次操作未执行";

  private final RunRepository runs;
  private final DomainResolver resolver;
  private final ToolRegistryClient registry;
  private final ToolGatewayClient gateway;
  private final LlmClient llm;
  private final ScreenRegistry screens;
  private final RecheckRegistry rechecks;
  private final ToolDisplayNames displayNames;
  private final ConfirmationTokenService tokens;
  private final SchemaValidator validator;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final AtomicLong callSeq = new AtomicLong();

  /** 每个 Run 最近一次下发的 UI（供 GET /agent/runs/{id}）。 */
  private final Map<String, UiSchema> lastUi = new java.util.concurrent.ConcurrentHashMap<>();

  /** 确认屏所需的中间结果缓存（订单详情 / 资格 / 试算），按 runId。 */
  private final Map<String, Map<String, JsonNode>> stepOutputs = new ConcurrentHashMap<>();

  /** 确认路径的按 Run 互斥锁（agent-safety §3：同一 Run 同时只处理一个确认；拿不到锁立即拒绝，不排队占用执行器）。 */
  private final Map<String, ReentrantLock> confirmLocks = new ConcurrentHashMap<>();

  public RunOrchestrator(
      RunRepository runs,
      DomainResolver resolver,
      ToolRegistryClient registry,
      ToolGatewayClient gateway,
      LlmClient llm,
      ScreenRegistry screens,
      RecheckRegistry rechecks,
      ToolDisplayNames displayNames,
      ConfirmationTokenService tokens,
      SchemaValidator validator,
      Clock clock) {
    this.runs = runs;
    this.resolver = resolver;
    this.registry = registry;
    this.gateway = gateway;
    this.llm = llm;
    this.screens = screens;
    this.rechecks = rechecks;
    this.displayNames = displayNames;
    this.tokens = tokens;
    this.validator = validator;
    this.mapper = validator.mapper();
    this.clock = clock;
  }

  // ------------------------------------------------------------------ 入口 1：新 Run

  public String start(
      IntentRequest intent, Principal principal, String traceId, RunEventSink sink) {
    String runId = "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    Run run = new Run(runId, intent.conversationId(), principal, intent.message(), now());
    runs.save(run);
    MDC.put("runId", runId);
    try {
      emit(
          sink,
          SseEvent.RUN_STARTED,
          new SseEvent.RunStartedData(runId, intent.conversationId(), now()));
      run.transition(RunState.PLANNING, now());

      IntentRequest.SelectedEntity selected =
          intent.pageContext() == null ? null : intent.pageContext().selectedEntity();
      DomainResolver.RouteDecision route =
          resolver.resolve(
              intent.message(),
              Optional.ofNullable(selected).map(IntentRequest.SelectedEntity::type),
              principal);
      Optional<String> domain = route.domain();
      log.info("route runId={} domain={} source={}", runId, domain.orElse("-"), route.source());
      if (domain.isEmpty()) {
        emit(
            sink, SseEvent.MESSAGE_DELTA, new SseEvent.MessageDeltaData(runId, NO_CAPABILITY_TEXT));
        complete(run, sink);
        return runId;
      }

      ToolSearch.Response found =
          registry.search(
              new ToolSearch.Request(
                  domain.get(),
                  null,
                  new ToolSearch.Principal(principal.userId(), principal.tenantId()),
                  intent.pageContext() != null && intent.pageContext().selectedEntity() != null
                      ? new ToolSearch.Context(intent.pageContext().selectedEntity().type())
                      : null));
      if (found.tools().isEmpty()) {
        emit(
            sink, SseEvent.MESSAGE_DELTA, new SseEvent.MessageDeltaData(runId, NO_CAPABILITY_TEXT));
        complete(run, sink);
        return runId;
      }

      // 实体抽取（消息正则优先，页面实体补位）；日志只记类型与 ID，不记原文
      Map<String, String> entities = EntityExtractor.extract(intent.message(), selected);
      log.info("entities runId={} {}", runId, entities);

      // 规划前拦截：领域内全部候选都需要实体而没有 → 提示并结束，不进规划、不调 Gateway
      Optional<String> needEntity =
          EntityRequirementCheck.check(domain.get(), found.tools(), entities);
      if (needEntity.isPresent()) {
        log.info("entity required but missing runId={} domain={}", runId, domain.get());
        emit(sink, SseEvent.MESSAGE_DELTA, new SseEvent.MessageDeltaData(runId, needEntity.get()));
        complete(run, sink);
        return runId;
      }

      Plan plan;
      try {
        plan =
            llm.plan(
                new LlmClient.PlanRequest(intent.message(), domain.get(), found.tools(), entities));
      } catch (LlmClient.MissingEntity e) {
        // 动词命中但目标工具缺实体（如无号码的「删除订单」）：与拦截同样的友好提示，不算失败
        log.info(
            "target entity missing runId={} domain={} entity={}",
            runId,
            domain.get(),
            e.entityType());
        emit(
            sink,
            SseEvent.MESSAGE_DELTA,
            new SseEvent.MessageDeltaData(runId, EntityRequirementCheck.text(domain.get())));
        complete(run, sink);
        return runId;
      }
      run.attachPlan(plan, now());
      log.info(
          "plan attached runId={} domain={} steps={} planner={}",
          runId,
          domain.get(),
          plan.steps().size(),
          llm.name());

      run.transition(RunState.EXECUTING, now());
      runSteps(run, traceId, sink);
      return runId;
    } catch (RunFailure e) {
      fail(run, e, sink);
      return runId;
    } catch (RuntimeException e) {
      log.error("run_unhandled runId={}", runId, e);
      fail(run, new RunFailure(RunFailureCode.INTERNAL_ERROR.name(), "internal error", e), sink);
      return runId;
    } finally {
      MDC.remove("runId");
    }
  }

  // ------------------------------------------------------------------ 入口 2：确认动作

  public void confirm(
      String runId,
      String actionId,
      String rawToken,
      Map<String, Object> formData,
      Principal principal,
      String traceId,
      RunEventSink sink) {
    Run run = runs.find(runId).orElseThrow(() -> new RunNotFound(runId));
    MDC.put("runId", runId);
    ReentrantLock lock = confirmLocks.computeIfAbsent(runId, k -> new ReentrantLock());
    if (!lock.tryLock()) {
      // 另一条确认正在执行：不排队（排队会占满 agent-run 线程池），直接拒绝本次请求
      try {
        rejectRequest(run, "another confirmation is in progress", sink);
      } finally {
        MDC.remove("runId");
      }
      return;
    }
    try {
      try {
        // ---- 前置校验：任一失败只拒绝本次请求，不改变 Run 状态（并发 / 重放不能破坏执行中的 Run）
        if (run.state() != RunState.WAITING_CONFIRMATION) {
          rejectRequest(run, "run not waiting for confirmation: " + run.state(), sink);
          return;
        }
        if (!run.principal().equals(principal)) {
          rejectRequest(run, "principal mismatch", sink);
          return;
        }
        Step step =
            run.currentStep()
                .orElseThrow(
                    () -> new RunFailure(RunFailureCode.INTERNAL_ERROR.name(), "no pending step"));
        ConfirmationToken token;
        try {
          token = tokens.consume(rawToken, runId, actionId, argsDigest(step.fixedArgs()), formData);
        } catch (ConfirmationTokenService.TokenUnknown e) {
          rejectRequest(run, e.getMessage(), sink);
          return;
        }
        log.info(
            "confirmation accepted runId={} step={} tool={}", runId, step.seq(), step.toolId());

        // ---- 令牌已消费：此后的失败才把 Run 置为 FAILED
        run.transition(RunState.EXECUTING, now());
        executeConfirmed(run, step, token, formData, traceId, sink);
      } catch (RunFailure e) {
        fail(run, e, sink);
      } catch (RuntimeException e) {
        log.error("confirm_unhandled runId={}", runId, e);
        fail(run, new RunFailure(RunFailureCode.INTERNAL_ERROR.name(), "internal error", e), sink);
      } finally {
        MDC.remove("runId");
        if (run.state().terminal()) {
          stepOutputs.remove(runId);
        }
      }
    } finally {
      lock.unlock();
      if (run.state().terminal()) {
        confirmLocks.remove(runId);
      }
    }
  }

  /** 令牌消费后的执行：查领域 ConfirmationRecheck → 经 Gateway 重调只读工具 → 领域判定 → 合并可信参数 → 执行目标工具 → 结果屏。 */
  private void executeConfirmed(
      Run run,
      Step step,
      ConfirmationToken token,
      Map<String, Object> formData,
      String traceId,
      RunEventSink sink) {
    String runId = run.runId();
    ConfirmationRecheck rc =
        rechecks
            .find(step.toolId())
            .orElseThrow(
                () ->
                    new RunFailure(
                        RunFailureCode.INTERNAL_ERROR.name(),
                        "no ConfirmationRecheck for " + step.toolId()));
    // recheck 工具版本取计划中同 toolId 的前置只读步骤（三个 recheck 都恰是各自前置步骤）；取不到 → INTERNAL_ERROR
    String recheckVersion =
        run.plan()
            .flatMap(
                p ->
                    p.steps().stream()
                        .filter(st -> st.toolId().equals(rc.recheckToolId()))
                        .map(Step::version)
                        .findFirst())
            .orElseThrow(
                () ->
                    new RunFailure(
                        RunFailureCode.INTERNAL_ERROR.name(),
                        "recheck tool not in plan: " + rc.recheckToolId()));
    JsonNode recheck;
    try {
      recheck =
          invoke(
              run,
              rc.recheckToolId(),
              recheckVersion,
              rc.recheckArgs(step.fixedArgs()),
              traceId,
              sink,
              "recheck");
    } catch (ToolCallFailed e) {
      throw e.gatewayCode() == ToolInvoke.ErrorCode.FORBIDDEN
          ? new RunFailure(RunFailureCode.CONFIRMATION_REJECTED.name(), "permission denied", e)
          : e;
    }
    UiSchema shown = lastUi.get(runId);
    Optional<String> rejected =
        rc.reject(recheck, shown == null ? mapper.createObjectNode() : mapper.valueToTree(shown));
    if (rejected.isPresent()) {
      // 内部原因只进日志；用户看到的是策略类文案（区分于令牌 / 并发拒绝）
      throw RunFailure.withUserText(
          RunFailureCode.CONFIRMATION_REJECTED.name(), rejected.get(), POLICY_REJECTED_TEXT);
    }

    // 合并 formData（键已在 consume 中校验为白名单内），值转字符串；可信参数由重校验结果覆盖
    Map<String, String> args = new LinkedHashMap<>(step.fixedArgs());
    formData.forEach((k, v) -> args.put(k, String.valueOf(v)));
    args.putAll(rc.trustedArgs(recheck));
    JsonNode created;
    try {
      created = invoke(run, step.toolId(), step.version(), args, traceId, sink, "step");
    } catch (ToolCallFailed e) {
      throw e.gatewayCode() == ToolInvoke.ErrorCode.FORBIDDEN
          ? new RunFailure(RunFailureCode.CONFIRMATION_REJECTED.name(), "permission denied", e)
          : e;
    }
    run.advance(now());
    log.info("confirmed step executed runId={} tokenStep={}", runId, token.stepSeq());

    UiSchema result = screens.result(step.toolId(), created, screenContext(run));
    lastUi.put(runId, result);
    emit(sink, SseEvent.UI_REPLACE, new SseEvent.UiReplaceData(runId, result));
    complete(run, sink);
  }

  /** 拒绝本次确认请求但不改变 Run 状态：向该连接发 run.failed{CONFIRMATION_REJECTED} 并关闭。 */
  private void rejectRequest(Run run, String internalReason, RunEventSink sink) {
    log.warn(
        "confirmation rejected runId={} state={} reason={}",
        run.runId(),
        run.state(),
        internalReason);
    try {
      emit(
          sink,
          SseEvent.RUN_FAILED,
          new SseEvent.RunFailedData(
              run.runId(),
              RunFailureCode.CONFIRMATION_REJECTED,
              userMessage(RunFailureCode.CONFIRMATION_REJECTED.name()),
              now()));
    } finally {
      sink.close();
    }
  }

  public Optional<Run> find(String runId) {
    return runs.find(runId);
  }

  public Optional<UiSchema> lastUi(String runId) {
    return Optional.ofNullable(lastUi.get(runId));
  }

  // ------------------------------------------------------------------ 内部

  private void runSteps(Run run, String traceId, RunEventSink sink) {
    while (true) {
      Optional<Step> cur = run.currentStep();
      if (cur.isEmpty()) {
        // 结果屏 = 计划中最后一个成功步骤的 result（中间只读步骤不发 ui.replace）
        lastExecuted(run)
            .ifPresent(
                last -> {
                  UiSchema ui =
                      screens.result(
                          last.toolId(),
                          stepOutputs
                              .getOrDefault(run.runId(), Map.of())
                              .getOrDefault(last.toolId(), mapper.createObjectNode()),
                          screenContext(run));
                  lastUi.put(run.runId(), ui);
                  emit(sink, SseEvent.UI_REPLACE, new SseEvent.UiReplaceData(run.runId(), ui));
                });
        complete(run, sink);
        return;
      }
      Step step = cur.get();
      if (step.requiresConfirmation()) {
        waitForConfirmation(run, step, sink);
        return;
      }
      JsonNode out =
          invoke(run, step.toolId(), step.version(), step.fixedArgs(), traceId, sink, "step");
      stepOutputs
          .computeIfAbsent(run.runId(), k -> new java.util.concurrent.ConcurrentHashMap<>())
          .put(step.toolId(), out);
      run.advance(now());
    }
  }

  private void waitForConfirmation(Run run, Step step, RunEventSink sink) {
    if (!screens.coversConfirmation(step.toolId())) {
      // fail-closed：需确认工具没有领域确认屏就不用兜底屏放行
      throw new RunFailure(
          RunFailureCode.INTERNAL_ERROR.name(), "no confirmation screen for " + step.toolId());
    }
    Map<String, JsonNode> cache = stepOutputs.getOrDefault(run.runId(), Map.of());
    ScreenContext ctx = screenContext(run);
    // 两遍生成：令牌绑定 actionId 且屏需要 token 字符串，先用占位令牌读出 action id 与 Form 字段白名单，再签发正式令牌生成正式屏
    UiSchema probe =
        screens.confirmation(
            step.toolId(), step.fixedArgs(), cache, ScreenRegistry.PLACEHOLDER_TOKEN, ctx);
    String actionId = ScreenRegistry.submitActionId(probe);
    Set<String> formKeys = ScreenRegistry.formKeys(probe);
    ConfirmationToken token =
        tokens.issue(run.runId(), actionId, step.seq(), argsDigest(step.fixedArgs()), formKeys);
    UiSchema ui = screens.confirmation(step.toolId(), step.fixedArgs(), cache, token.token(), ctx);
    if (!ScreenRegistry.formKeys(ui).equals(token.allowedFormKeys())
        || !ScreenRegistry.submitActionId(ui).equals(actionId)) {
      throw new RunFailure(
          RunFailureCode.INTERNAL_ERROR.name(),
          "confirmation screen not stable across token issue");
    }
    lastUi.put(run.runId(), ui);
    emit(sink, SseEvent.UI_REPLACE, new SseEvent.UiReplaceData(run.runId(), ui));
    emit(
        sink,
        SseEvent.CONFIRMATION_REQUIRED,
        new SseEvent.ConfirmationRequiredData(run.runId(), actionId, token.expiresAt()));
    run.transition(RunState.WAITING_CONFIRMATION, now());
    runs.save(run);
    sink.close();
  }

  private static ScreenContext screenContext(Run run) {
    return new ScreenContext(run.runId(), run.principal().userId(), run.principal().tenantId());
  }

  /** 计划中最后一个已执行的步骤（nextSeq - 1）。 */
  private static Optional<Step> lastExecuted(Run run) {
    int seq = run.nextSeq() - 1;
    return seq < 1 ? Optional.empty() : run.plan().map(p -> p.step(seq));
  }

  /** 一次工具调用：tool.selected → tool.started → Gateway → tool.completed。失败转 RunFailure。 */
  private JsonNode invoke(
      Run run,
      String toolId,
      String version,
      Map<String, String> args,
      String traceId,
      RunEventSink sink,
      String kind) {
    String toolCallId = "tc_" + String.format("%06d", callSeq.incrementAndGet());
    String displayName = displayNames.of(toolId);
    emit(
        sink,
        SseEvent.TOOL_SELECTED,
        new SseEvent.ToolSelectedData(run.runId(), toolCallId, toolId, version, displayName));
    emit(sink, SseEvent.TOOL_STARTED, new SseEvent.ToolStartedData(run.runId(), toolCallId, now()));
    ObjectNode argNode = mapper.createObjectNode();
    args.forEach(argNode::put);
    String idem =
        run.runId()
            + "-"
            + toolId
            + "-"
            + ("recheck".equals(kind) ? "recheck" : String.valueOf(run.nextSeq()));
    ToolInvoke.Request req =
        new ToolInvoke.Request(
            toolId,
            version,
            argNode,
            new ToolInvoke.ExecutionContext(
                run.runId(),
                toolCallId,
                run.principal().userId(),
                run.principal().tenantId(),
                idem,
                traceId));
    long t0 = System.nanoTime();
    ToolInvoke.Response resp;
    try {
      resp = gateway.invoke(req);
    } catch (RuntimeException e) {
      // 端口实现自身异常（非 Gateway 契约失败）：传输层错误
      long ms = (System.nanoTime() - t0) / 1_000_000;
      emit(
          sink,
          SseEvent.TOOL_COMPLETED,
          new SseEvent.ToolCompletedData(
              run.runId(), toolCallId, SseEvent.ToolStatus.failed, ms, null));
      log.warn(
          "tool invocation transport failure runId={} tool={} cause={}",
          run.runId(),
          toolId,
          e.toString());
      throw new ToolCallFailed(RunFailureCode.TOOL_EXECUTION_FAILED, null, toolId, e);
    }
    long ms = (System.nanoTime() - t0) / 1_000_000;
    emit(
        sink,
        SseEvent.TOOL_COMPLETED,
        new SseEvent.ToolCompletedData(
            run.runId(), toolCallId, resp.status(), ms, screens.summary(toolId, resp.output())));
    if (resp.status() != SseEvent.ToolStatus.succeeded) {
      // 按 tool-invoke.response.error.code 结构化映射（spec §4.2），不嗅探异常文本
      ToolInvoke.ErrorCode gw = resp.error() == null ? null : resp.error().code();
      RunFailureCode code =
          gw == ToolInvoke.ErrorCode.OUTPUT_INVALID
              ? RunFailureCode.TOOL_OUTPUT_INVALID
              : RunFailureCode.TOOL_EXECUTION_FAILED;
      log.warn("tool invocation failed runId={} tool={} gatewayCode={}", run.runId(), toolId, gw);
      throw new ToolCallFailed(code, gw, toolId, null);
    }
    return resp.output();
  }

  /** 一次工具调用失败：携带 Gateway 结构化错误码，供确认路径把 FORBIDDEN 映射为 CONFIRMATION_REJECTED。 */
  static final class ToolCallFailed extends RunFailure {
    private static final long serialVersionUID = 1L;
    private final transient ToolInvoke.ErrorCode gatewayCode;

    ToolCallFailed(
        RunFailureCode code, ToolInvoke.ErrorCode gatewayCode, String toolId, Throwable cause) {
      super(code.name(), "tool " + toolId + " failed", cause);
      this.gatewayCode = gatewayCode;
    }

    ToolInvoke.ErrorCode gatewayCode() {
      return gatewayCode;
    }
  }

  private void complete(Run run, RunEventSink sink) {
    run.transition(RunState.COMPLETED, now());
    runs.save(run);
    stepOutputs.remove(run.runId());
    emit(sink, SseEvent.RUN_COMPLETED, new SseEvent.RunCompletedData(run.runId(), now()));
    sink.close();
  }

  private void fail(Run run, RunFailure e, RunEventSink sink) {
    log.warn("run failed runId={} code={} reason={}", run.runId(), e.code(), e.getMessage());
    if (!run.state().terminal()) {
      run.fail(e.code(), now());
    }
    runs.save(run);
    stepOutputs.remove(run.runId());
    try {
      emit(
          sink,
          SseEvent.RUN_FAILED,
          new SseEvent.RunFailedData(
              run.runId(),
              RunFailureCode.valueOf(e.code()),
              e.userText() != null ? e.userText() : userMessage(e.code()),
              now()));
    } finally {
      sink.close();
    }
  }

  private static String userMessage(String code) {
    return switch (code) {
      case "CONFIRMATION_REJECTED" -> "确认已过期或已被使用，请重新发起";
      case "TOOL_SELECTION_INVALID" -> "暂时无法为该请求制定可执行的方案";
      case "TOOL_OUTPUT_INVALID", "TOOL_EXECUTION_FAILED" -> "执行过程中工具调用失败，请稍后重试";
      default -> "系统内部错误";
    };
  }

  private void emit(RunEventSink sink, String event, Object data) {
    JsonNode dataNode = mapper.valueToTree(data);
    ObjectNode frame = mapper.createObjectNode();
    frame.put("event", event);
    frame.set("data", dataNode);
    validator.assertValid("sse-events", frame);
    sink.emit(new SseEvent(event, dataNode));
  }

  /** argsDigest = SHA-256(规范化 JSON) 前 16 字节；规范化 = 键排序。 */
  static String argsDigest(Map<String, String> args) {
    String canonical = new TreeMap<>(args).toString();
    try {
      byte[] h =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(h, 0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Instant now() {
    return Instant.now(clock);
  }

  /** runId 不存在 → 404。 */
  public static class RunNotFound extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RunNotFound(String runId) {
      super("run not found: " + runId);
    }
  }
}
