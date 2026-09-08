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
import com.strato.runtime.domain.ConfirmationToken;
import com.strato.runtime.domain.Plan;
import com.strato.runtime.domain.Run;
import com.strato.runtime.domain.RunFailure;
import com.strato.runtime.domain.RunRepository;
import com.strato.runtime.domain.RunState;
import com.strato.runtime.domain.Step;
import com.strato.spi.Principal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
 * requiresConfirmation 生成 UI Schema + Token 并进入 WAITING_CONFIRMATION；确认后先经 Gateway 重校验资格，再执行目标工具。
 * 任何工具调用都经 ToolGatewayClient（agent-safety §1）。事件按 spec §4.0 发射，并在发出前经 sse-events 契约校验。
 *
 * <p>确认路径按 runId 互斥：并发 / 重放的确认请求要么等待，要么因令牌已消费被拒绝，且这类前置拒绝不改变 Run 状态， 保证成功执行的退款不会被并发请求报告为
 * FAILED。确认后金额一律取重校验结果并与确认屏展示值比对，模型或前端都无法决定金额。
 */
@Service
public class RunOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(RunOrchestrator.class);
  private static final String NO_CAPABILITY_TEXT = "当前没有可用能力处理该请求";
  private static final String RECHECK_TOOL = "refund.eligibility.check";

  private final RunRepository runs;
  private final DomainResolver resolver;
  private final ToolRegistryClient registry;
  private final ToolGatewayClient gateway;
  private final LlmClient llm;
  private final UiSchemaBuilder uiBuilder;
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
      UiSchemaBuilder uiBuilder,
      ConfirmationTokenService tokens,
      SchemaValidator validator,
      Clock clock) {
    this.runs = runs;
    this.resolver = resolver;
    this.registry = registry;
    this.gateway = gateway;
    this.llm = llm;
    this.uiBuilder = uiBuilder;
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

      // 规划前拦截：领域内全部候选都需要页面实体而上下文没有 → 提示并结束，不进规划、不调 Gateway
      Optional<String> needEntity =
          EntityRequirementCheck.check(domain.get(), found.tools(), selected);
      if (needEntity.isPresent()) {
        log.info("entity required but missing runId={} domain={}", runId, domain.get());
        emit(sink, SseEvent.MESSAGE_DELTA, new SseEvent.MessageDeltaData(runId, needEntity.get()));
        complete(run, sink);
        return runId;
      }

      Map<String, String> entity = new LinkedHashMap<>();
      if (intent.pageContext() != null && intent.pageContext().selectedEntity() != null) {
        entity.put("type", intent.pageContext().selectedEntity().type());
        entity.put("id", intent.pageContext().selectedEntity().id());
      }
      Plan plan =
          llm.plan(
              new LlmClient.PlanRequest(intent.message(), domain.get(), found.tools(), entity));
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

  /** 令牌消费后的执行：经 Gateway 重校验 → 金额取可信来源并与确认屏比对 → 执行目标工具 → 结果屏。 */
  private void executeConfirmed(
      Run run,
      Step step,
      ConfirmationToken token,
      Map<String, Object> formData,
      String traceId,
      RunEventSink sink) {
    String runId = run.runId();
    // 重校验：经 Gateway 再调一次资格检查（agent-safety §3；不直连领域服务）。权限不足在此处即 CONFIRMATION_REJECTED
    String orderId = step.fixedArgs().getOrDefault("orderId", "");
    JsonNode recheck;
    try {
      recheck =
          invoke(run, RECHECK_TOOL, "1.2.0", Map.of("orderId", orderId), traceId, sink, "recheck");
    } catch (ToolCallFailed e) {
      throw e.gatewayCode() == ToolInvoke.ErrorCode.FORBIDDEN
          ? new RunFailure(RunFailureCode.CONFIRMATION_REJECTED.name(), "permission denied", e)
          : e;
    }
    if (!recheck.path("eligible").asBoolean(false)) {
      throw new RunFailure(RunFailureCode.CONFIRMATION_REJECTED.name(), "order no longer eligible");
    }

    // 金额只信重校验结果，且必须等于确认屏 RefundConfirmCard 实际展示的金额（用户确认的就是执行的）
    String trustedAmount = recheck.path("refundableAmount").asText("");
    String shownAmount = shownRefundAmount(runId);
    if (trustedAmount.isEmpty() || !trustedAmount.equals(shownAmount)) {
      throw new RunFailure(
          RunFailureCode.CONFIRMATION_REJECTED.name(),
          "refundable amount changed since confirmation");
    }

    // 合并 formData（键已在 consume 中校验为白名单内），值转字符串；金额由可信来源覆盖
    Map<String, String> args = new LinkedHashMap<>(step.fixedArgs());
    formData.forEach((k, v) -> args.put(k, String.valueOf(v)));
    args.put("amount", trustedAmount);
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

    UiSchema result = uiBuilder.refundResult(created);
    lastUi.put(runId, result);
    emit(sink, SseEvent.UI_REPLACE, new SseEvent.UiReplaceData(runId, result));
    complete(run, sink);
  }

  /** 确认屏上 RefundConfirmCard 展示的金额（取自已下发的 UI，而不是重算），没有则为空串。 */
  private String shownRefundAmount(String runId) {
    UiSchema ui = lastUi.get(runId);
    if (ui == null) {
      return "";
    }
    return ui.components().stream()
        .filter(c -> c.type() == UiSchema.ComponentType.RefundConfirmCard)
        .map(c -> c.props().path("amount").asText(""))
        .findFirst()
        .orElse("");
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
    Map<String, JsonNode> cache = stepOutputs.getOrDefault(run.runId(), Map.of());
    String orderId = step.fixedArgs().getOrDefault("orderId", "");
    // 令牌先于 UI 生成：UI 需要 token 字符串；白名单在 UI 生成后再回填校验 —— 先用固定字段名集合（Form 只声明 reason）
    ConfirmationToken token =
        tokens.issue(
            run.runId(),
            UiSchemaBuilder.CONFIRM_ACTION_ID,
            step.seq(),
            argsDigest(step.fixedArgs()),
            java.util.Set.of(UiSchemaBuilder.REASON_FIELD));
    UiSchema ui =
        uiBuilder.refundConfirmation(
            orderId,
            cache.getOrDefault("order.detail.get", mapper.createObjectNode()),
            cache.getOrDefault("refund.eligibility.check", mapper.createObjectNode()),
            cache.getOrDefault("refund.preview", mapper.createObjectNode()),
            token.token());
    if (!UiSchemaBuilder.formKeys(ui).equals(token.allowedFormKeys())) {
      throw new RunFailure(
          RunFailureCode.INTERNAL_ERROR.name(), "form keys / token whitelist mismatch");
    }
    lastUi.put(run.runId(), ui);
    emit(sink, SseEvent.UI_REPLACE, new SseEvent.UiReplaceData(run.runId(), ui));
    emit(
        sink,
        SseEvent.CONFIRMATION_REQUIRED,
        new SseEvent.ConfirmationRequiredData(
            run.runId(), UiSchemaBuilder.CONFIRM_ACTION_ID, token.expiresAt()));
    run.transition(RunState.WAITING_CONFIRMATION, now());
    runs.save(run);
    sink.close();
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
    String displayName = ToolDisplayNames.of(toolId);
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
            run.runId(), toolCallId, resp.status(), ms, summaryOf(toolId, resp.output())));
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

  private static String summaryOf(String toolId, JsonNode out) {
    if (out == null) {
      return null;
    }
    return switch (toolId) {
      case "refund.eligibility.check" ->
          out.path("eligible").asBoolean(false) ? "订单满足退款条件" : "订单不满足退款条件";
      case "refund.preview" ->
          "可退 "
              + out.path("amount").asText("")
              + " 元，预计 "
              + out.path("estimatedDays").asText("")
              + " 天到账";
      case "refund.create" -> "退款单 " + out.path("refundId").asText("") + " 已提交";
      default -> null;
    };
  }

  private void complete(Run run, RunEventSink sink) {
    run.transition(RunState.COMPLETED, now());
    runs.save(run);
    emit(sink, SseEvent.RUN_COMPLETED, new SseEvent.RunCompletedData(run.runId(), now()));
    sink.close();
  }

  private void fail(Run run, RunFailure e, RunEventSink sink) {
    log.warn("run failed runId={} code={} reason={}", run.runId(), e.code(), e.getMessage());
    if (!run.state().terminal()) {
      run.fail(e.code(), now());
    }
    runs.save(run);
    try {
      emit(
          sink,
          SseEvent.RUN_FAILED,
          new SseEvent.RunFailedData(
              run.runId(), RunFailureCode.valueOf(e.code()), userMessage(e.code()), now()));
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
