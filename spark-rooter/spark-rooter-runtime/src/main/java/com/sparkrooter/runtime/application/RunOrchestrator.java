package com.sparkrooter.runtime.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.contracts.model.IntentRequest;
import com.sparkrooter.contracts.model.RunFailureCode;
import com.sparkrooter.contracts.model.SseEvent;
import com.sparkrooter.contracts.model.ToolInvoke;
import com.sparkrooter.contracts.model.ToolSearch;
import com.sparkrooter.contracts.model.UiSchema;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.application.port.RunEventSink;
import com.sparkrooter.runtime.application.port.ToolGatewayClient;
import com.sparkrooter.runtime.application.port.ToolRegistryClient;
import com.sparkrooter.runtime.application.screen.ClarificationScreen;
import com.sparkrooter.runtime.application.screen.ScreenRegistry;
import com.sparkrooter.runtime.domain.ConfirmationToken;
import com.sparkrooter.runtime.domain.Plan;
import com.sparkrooter.runtime.domain.Run;
import com.sparkrooter.runtime.domain.RunFailure;
import com.sparkrooter.runtime.domain.RunRepository;
import com.sparkrooter.runtime.domain.RunState;
import com.sparkrooter.runtime.domain.Step;
import com.sparkrooter.spi.ConfirmationRecheck;
import com.sparkrooter.spi.ConversationMemory;
import com.sparkrooter.spi.RunMetricsSink;
import com.sparkrooter.spi.ScreenContext;
import com.sparkrooter.spi.tool.ToolMeta;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Run 编排器（决策面 / 状态面）。 意图 → 领域路由 → Registry 搜索 → LLM 规划 → 逐步执行：低风险经 Gateway 自动执行；遇
 * requiresConfirmation 生成 UI Schema + Token 并进入 WAITING_CONFIRMATION；确认后先经 Gateway 重校验，再执行目标工具。
 * 任何工具调用都经 ToolGatewayClient（agent-safety §1）。事件按 spec §4.0 发射，并在发出前经 sse-events 契约校验。
 *
 * <p>屏与领域策略都不在这里：确认屏 / 结果屏查 ScreenRegistry（领域 ScreenBuilder），确认后重校验查 RecheckRegistry（领域
 * ConfirmationRecheck）；需确认工具缺任一者 → fail-closed INTERNAL_ERROR。
 *
 * <p><b>本类不持有按 Run 的进程内状态</b>：最近一次屏、前置步骤输出、步骤 inputSchema、是否出过澄清屏都在 {@link Run} 聚合里随 {@link
 * RunRepository} 走，确认请求落到任意 hub 副本都能凭仓储里的那条记录完成。
 *
 * <p>确认路径的互斥靠<b>令牌原子消费</b>（agent-safety §3）：并发 / 重放的确认请求里只有一个能 consume 到令牌，其余得到 TokenUnknown
 * 而被拒绝，且这类前置拒绝不改变 Run 状态， 保证成功执行的写操作不会被并发请求报告为 FAILED。可信参数（如金额）一律取重校验结果并与确认屏展示值比对， 模型或前端都无法决定。
 */
public class RunOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(RunOrchestrator.class);

  /**
   * 过载时给用户的文案。
   *
   * <p>与 {@code AgentRunController.OVERLOADED_TEXT} 必须一致——两处都是「系统忙」的同一语义
   * （那边是编排池拒绝，这边是会话并发超限），文案不同会让用户以为是两种问题。
   *
   * <p>不共享常量是因为方向：runtime 不能依赖 web-mvc（project-structure §2）。字符串重复两份 优于反向依赖。
   */
  private static final String OVERLOADED_TEXT = "当前请求较多，请稍后重试";

  private static final String NO_CAPABILITY_TEXT = "当前没有可用能力处理该请求";

  /** 领域重校验策略拒绝时的用户文案（区分于令牌 / 并发拒绝）。 */
  private static final String POLICY_REJECTED_TEXT = "对象状态已变化，本次操作未执行";

  private final RunRepository runs;
  private final ToolRegistryClient registry;
  private final ToolGatewayClient gateway;
  private final LlmClient llm;
  private final ScreenRegistry screens;
  private final RecheckRegistry rechecks;
  private final RunMetricsSink runMetrics;
  private final ToolDisplayNames displayNames;
  private final ConfirmationTokenService tokens;
  private final ToolMetaRegistry meta;
  private final ConversationMemory memory;
  private final SchemaValidator validator;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final AtomicLong callSeq = new AtomicLong();

  public RunOrchestrator(
      RunRepository runs,
      ToolRegistryClient registry,
      ToolGatewayClient gateway,
      LlmClient llm,
      ScreenRegistry screens,
      RecheckRegistry rechecks,
      ToolDisplayNames displayNames,
      ConfirmationTokenService tokens,
      ToolMetaRegistry meta,
      ConversationMemory memory,
      SchemaValidator validator,
      RunMetricsSink runMetrics,
      Clock clock) {
    this.runs = runs;
    this.registry = registry;
    this.gateway = gateway;
    this.llm = llm;
    this.screens = screens;
    this.rechecks = rechecks;
    this.runMetrics = runMetrics;
    this.displayNames = displayNames;
    this.tokens = tokens;
    this.meta = meta;
    this.memory = memory;
    this.validator = validator;
    this.mapper = validator.mapper();
    this.clock = clock;
  }

  // ------------------------------------------------------------------ 入口 1：新 Run

  /**
   * @param sessionId 宿主 SessionIdResolver 在请求线程解析出的会话键；Run 查询、确认令牌都按它隔离
   */
  public String start(IntentRequest intent, String sessionId, String traceId, RunEventSink sink) {
    String runId = "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    Run run = new Run(runId, intent.conversationId(), sessionId, intent.message(), now());
    runs.save(run);
    MDC.put("runId", runId);
    try {
      emit(
          sink,
          SseEvent.RUN_STARTED,
          new SseEvent.RunStartedData(runId, intent.conversationId(), now()));
      run.transition(RunState.PLANNING, now());

      // ① 会话装载：记忆实体、最近列表行 ID、上一轮挂起的原话（只有 ID 与短文本）
      Optional<ConversationMemory.Memory> remembered =
          memory.find(sessionId, intent.conversationId());
      LlmClient.Context ctx =
          remembered
              .map(
                  m ->
                      new LlmClient.Context(
                          m.entities(),
                          m.lastTable() == null ? List.of() : m.lastTable().rowIds(),
                          Optional.ofNullable(
                              m.lastTable() == null ? null : m.lastTable().pendingMessage())))
              .orElse(LlmClient.Context.empty());

      // ② 候选发现：全部可发现工具（不按领域筛，内核不认识领域）；宿主 ToolAccessPolicy 按 sessionId 过滤
      ToolSearch.Response found =
          registry.search(new ToolSearch.Request(null, null, null), sessionId);
      if (found.tools().isEmpty()) {
        emit(
            sink, SseEvent.MESSAGE_DELTA, new SseEvent.MessageDeltaData(runId, NO_CAPABILITY_TEXT));
        complete(run, sink);
        return runId;
      }

      // ③ 模型规划 + ④ 通用校验（都在 LlmClient 内）；日志不记原话
      LlmClient.Decision decision =
          llm.plan(new LlmClient.PlanRequest(intent.message(), found.tools(), ctx));
      log.info(
          "decision runId={} kind={} planner={}",
          runId,
          decision.getClass().getSimpleName(),
          llm.name());

      Plan plan;
      switch (decision) {
        case LlmClient.NoCapability n -> {
          emit(sink, SseEvent.MESSAGE_DELTA, new SseEvent.MessageDeltaData(runId, n.reply()));
          complete(run, sink);
          return runId;
        }
        case LlmClient.Clarify c -> {
          // ⑤ 缺实体：有澄清候选源 → 列表让用户点选；否则把模型的追问回给用户
          if (!clarify(run, intent.message(), c.entityType(), traceId, sink)) {
            emit(sink, SseEvent.MESSAGE_DELTA, new SseEvent.MessageDeltaData(runId, c.reply()));
          }
          complete(run, sink);
          return runId;
        }
        case LlmClient.Planned p -> plan = p.plan();
      }
      // 计划各步骤的 inputSchema 随 Run 快照：确认可能落到另一副本，那时不能再依赖注册表的当下状态
      run.attachPlan(plan, schemaSnapshot(found), now());
      log.info(
          "plan attached runId={} steps={} planner={}", runId, plan.steps().size(), llm.name());

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
      String sessionId,
      String traceId,
      RunEventSink sink) {
    Run run = runs.find(runId).orElseThrow(() -> new RunNotFound(runId));
    MDC.put("runId", runId);
    try {
      // ---- 前置校验：任一失败只拒绝本次请求，不改变 Run 状态（并发 / 重放不能破坏执行中的 Run）
      if (run.state() != RunState.WAITING_CONFIRMATION) {
        rejectRequest(run, "run not waiting for confirmation: " + run.state(), sink);
        return;
      }
      if (!run.sessionId().equals(sessionId)) {
        rejectRequest(run, "session mismatch", sink);
        return;
      }
      Step step =
          run.currentStep()
              .orElseThrow(
                  () -> new RunFailure(RunFailureCode.INTERNAL_ERROR.name(), "no pending step"));
      ConfirmationToken token;
      try {
        // 原子消费：并发 / 重放请求里只有一个能拿到令牌，这就是确认路径唯一需要的互斥
        token =
            tokens.consume(
                rawToken,
                runId,
                actionId,
                argsDigest(step.fixedArgs()),
                run.conversationId(),
                sessionId,
                formData);
      } catch (ConfirmationTokenService.TokenUnknown e) {
        rejectRequest(run, e.getMessage(), sink);
        return;
      }
      log.info("confirmation accepted runId={} step={} tool={}", runId, step.seq(), step.toolId());

      // ---- 令牌已消费：此后的失败才把 Run 置为 FAILED。
      // 立即落库：共享存储下每次 find 是新副本，不 save 则另一副本的 GET 在执行窗口内看到过时状态
      run.transition(RunState.EXECUTING, now());
      runs.save(run);
      executeConfirmed(run, step, token, formData, traceId, sink);
    } catch (RunFailure e) {
      fail(run, e, sink);
    } catch (RuntimeException e) {
      log.error("confirm_unhandled runId={}", runId, e);
      fail(run, new RunFailure(RunFailureCode.INTERNAL_ERROR.name(), "internal error", e), sink);
    } finally {
      MDC.remove("runId");
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
    JsonNode shown = run.currentUi().map(this::readTree).orElseGet(mapper::createObjectNode);
    Optional<String> rejected = rc.reject(recheck, shown);
    if (rejected.isPresent()) {
      // 内部原因只进日志；用户看到的是策略类文案（区分于令牌 / 并发拒绝）
      throw RunFailure.withUserText(
          RunFailureCode.CONFIRMATION_REJECTED.name(), rejected.get(), POLICY_REJECTED_TEXT);
    }

    // 合并 formData（键已在 consume 中校验为白名单内），值转字符串；可信参数由重校验结果覆盖
    Map<String, String> args = new LinkedHashMap<>(step.fixedArgs());
    formData.forEach((k, v) -> args.put(k, String.valueOf(v)));
    Map<String, String> trusted = rc.trustedArgs(recheck);
    if (!rc.trustedArgKeys().containsAll(trusted.keySet())) {
      // 领域实现声明与产出不一致：声明用于自检互斥，产出用于覆盖，两者必须一致
      throw new RunFailure(
          RunFailureCode.INTERNAL_ERROR.name(),
          "trustedArgs keys not declared by trustedArgKeys for " + step.toolId());
    }
    args.putAll(trusted);
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
    setUi(run, result);
    emit(sink, SseEvent.UI_REPLACE, new SseEvent.UiReplaceData(runId, result));
    complete(run, sink);
  }

  /** 拒绝本次确认请求但不改变 Run 状态：向该连接发 run.failed{CONFIRMATION_REJECTED} 并关闭。 */
  private void rejectRequest(Run run, String internalReason, RunEventSink sink) {
    // 独立出口：拒绝本次确认请求但不改 Run 状态。不埋这里会让"确认被拒"在成功率里看不见
    recordRunMetrics(run, "confirmation_rejected");
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

  /** 最近一次下发的屏：从仓储读，不在本实例缓存——GET 可能落到与执行不同的副本。 */
  public Optional<UiSchema> lastUi(String runId) {
    return runs.find(runId)
        .flatMap(Run::currentUi)
        .map(
            json -> {
              try {
                return mapper.readValue(json, UiSchema.class);
              } catch (JsonProcessingException e) {
                throw new IllegalStateException("stored currentUi is not a UiSchema", e);
              }
            });
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
                  JsonNode out =
                      run.stepOutput(last.toolId())
                          .map(this::readTree)
                          .orElseGet(mapper::createObjectNode);
                  UiSchema ui = screens.result(last.toolId(), out, screenContext(run));
                  setUi(run, ui);
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
      // 客户端已断开：只读步骤没人看结果，白烧一次工具调用；写步骤照跑（不留半截的写链）。
      // sideEffect 取不到（如 http provider 工具无 ToolMeta）视为写，宁多跑一步只读不砍掉一步写
      if (sink.isClosed() && !isWrite(step.toolId())) {
        log.info(
            "run abandoned runId={} step={} tool={} reason=client_gone",
            run.runId(),
            step.seq(),
            step.toolId());
        throw new RunFailure(RunFailureCode.INTERNAL_ERROR.name(), "client gone");
      }
      JsonNode out =
          invoke(run, step.toolId(), step.version(), step.fixedArgs(), traceId, sink, "step");
      run.putStepOutput(step.toolId(), out.toString(), now());
      run.advance(now());
    }
  }

  private boolean isWrite(String toolId) {
    return meta.find(toolId).map(ToolMeta::sideEffect).orElse(true);
  }

  private void waitForConfirmation(Run run, Step step, RunEventSink sink) {
    if (!screens.coversConfirmation(step.toolId())) {
      // fail-closed：需确认工具没有领域确认屏就不用兜底屏放行
      throw new RunFailure(
          RunFailureCode.INTERNAL_ERROR.name(), "no confirmation screen for " + step.toolId());
    }
    Map<String, JsonNode> cache = new HashMap<>();
    run.stepOutputs().forEach((k, v) -> cache.put(k, readTree(v)));
    ScreenContext ctx = screenContext(run);
    // 两遍生成：令牌绑定 actionId 且屏需要 token 字符串，先用占位令牌读出 action id 与 Form 字段白名单，再签发正式令牌生成正式屏
    UiSchema probe =
        screens.confirmation(
            step.toolId(), step.fixedArgs(), cache, ScreenRegistry.PLACEHOLDER_TOKEN, ctx);
    String actionId = ScreenRegistry.submitActionId(probe);
    Set<String> formKeys = ScreenRegistry.formKeys(probe);
    ConfirmationToken token =
        tokens.issue(
            run.runId(),
            actionId,
            step.seq(),
            argsDigest(step.fixedArgs()),
            run.conversationId(),
            run.sessionId(),
            formKeys);
    UiSchema ui = screens.confirmation(step.toolId(), step.fixedArgs(), cache, token.token(), ctx);
    if (!ScreenRegistry.formKeys(ui).equals(token.allowedFormKeys())
        || !ScreenRegistry.submitActionId(ui).equals(actionId)) {
      throw new RunFailure(
          RunFailureCode.INTERNAL_ERROR.name(),
          "confirmation screen not stable across token issue");
    }
    setUi(run, ui);
    emit(sink, SseEvent.UI_REPLACE, new SseEvent.UiReplaceData(run.runId(), ui));
    emit(
        sink,
        SseEvent.CONFIRMATION_REQUIRED,
        new SseEvent.ConfirmationRequiredData(run.runId(), actionId, token.expiresAt()));
    run.transition(RunState.WAITING_CONFIRMATION, now());
    runs.save(run);
    sink.close();
  }

  /**
   * 澄清屏：查 clarifiesEntity == 缺失类型的工具 → 经 Gateway 调它（无参，全默认）拿原始输出 → ClarificationScreen 投影 → 契约校验 →
   * ui.replace + message.delta。无候选源 / 输出空 / 调用失败 → false，调用方走现状提示。
   */
  private boolean clarify(
      Run run, String message, String entityType, String traceId, RunEventSink sink) {
    if (entityType == null) {
      log.info("clarify: no entityType, falling back to message.delta");
      return false;
    }
    Optional<ToolMeta> clarifier = meta.clarifierFor(entityType);
    if (clarifier.isEmpty()) {
      log.info("clarify: no clarifier registered for entity={}", entityType);
      return false;
    }
    ToolMeta c = clarifier.get();
    JsonNode out;
    try {
      out = invoke(run, c.toolId(), c.version(), Map.of(), traceId, sink, "clarify");
    } catch (RunFailure e) {
      log.warn("clarification list failed runId={} tool={}", run.runId(), c.toolId());
      return false;
    }
    String label = meta.entityLabel(entityType);
    Optional<ObjectNode> screen = ClarificationScreen.build(entityType, label, "选择", message, out);
    if (screen.isEmpty()) {
      log.info("clarification screen: output empty or parse failed for entity={}", entityType);
      return false;
    }
    UiSchema ui = screens.toUi(screen.get());
    setUi(run, ui);
    emit(sink, SseEvent.UI_REPLACE, new SseEvent.UiReplaceData(run.runId(), ui));
    emit(
        sink,
        SseEvent.MESSAGE_DELTA,
        new SseEvent.MessageDeltaData(run.runId(), "请选择要操作的" + label));
    // 澄清屏也算「最近一次列表」并挂起原话：用户下一句「第二个」由模型结合上下文解析
    List<String> ids = new java.util.ArrayList<>();
    screen
        .get()
        .path("components")
        .get(0)
        .path("props")
        .path("rows")
        .forEach(r -> ids.add(r.path("id").asText()));
    memory.put(
        run.sessionId(),
        run.conversationId(),
        new ConversationMemory.Memory(
            c.domain(),
            Map.of(),
            new ConversationMemory.LastTable(c.toolId(), ids, message),
            now()));
    run.markClarified(now());
    log.info(
        "clarification screen runId={} entity={} tool={}", run.runId(), entityType, c.toolId());
    return true;
  }

  /**
   * 成功终态写入会话记忆：领域、已用实体、最近一次列表屏的行 ID（供下一轮省略实体 / 序数指代）。失败的 Run 不写（评审 N-3）。 出过澄清屏的 Run 由 clarify()
   * 写入，这里不再覆盖（评审 M-4：否则 lastTable.toolId 被空串覆盖，「第二个」失效）。
   */
  private void remember(Run run) {
    if (run.clarified()) {
      return;
    }
    Map<String, String> ents = new LinkedHashMap<>();
    ConversationMemory.LastTable lastTable = null;
    Optional<Plan> plan = run.plan();
    if (plan.isPresent()) {
      for (Step s : plan.get().steps()) {
        s.fixedArgs()
            .forEach(
                (k, v) -> {
                  String type = meta.entityTypeOf(k);
                  if (type != null) {
                    ents.put(type, v);
                  }
                });
      }
    }
    Optional<UiSchema> ui = run.currentUi().map(this::readUi);
    if (ui.isPresent()) {
      for (UiSchema.Component comp : ui.get().components()) {
        if (comp.type() == UiSchema.ComponentType.Table) {
          List<String> ids = new java.util.ArrayList<>();
          comp.props().path("rows").forEach(r -> ids.add(r.path("id").asText()));
          String toolId = lastExecuted(run).map(Step::toolId).orElse("");
          lastTable = new ConversationMemory.LastTable(toolId, ids, null);
        }
      }
    }
    // 按字段合并而不是整体覆盖：本轮没出列表屏时，上一轮的 lastTable（供「第二个」指代）必须保留；
    // 实体也是叠加（新值覆盖同类型旧值），否则一轮查详情就会把上一轮列表的行 ID 全丢掉
    Optional<ConversationMemory.Memory> prev = memory.find(run.sessionId(), run.conversationId());
    Map<String, String> mergedEnts = new LinkedHashMap<>();
    prev.ifPresent(m -> mergedEnts.putAll(m.entities()));
    mergedEnts.putAll(ents);
    ConversationMemory.LastTable mergedTable =
        lastTable != null ? lastTable : prev.map(ConversationMemory.Memory::lastTable).orElse(null);
    String domain =
        plan.map(Plan::domain)
            .filter(d -> !d.isBlank())
            .orElseGet(() -> prev.map(ConversationMemory.Memory::domain).orElse(""));
    if (mergedEnts.isEmpty() && mergedTable == null) {
      return;
    }
    memory.put(
        run.sessionId(),
        run.conversationId(),
        new ConversationMemory.Memory(domain, mergedEnts, mergedTable, now()));
  }

  private static ScreenContext screenContext(Run run) {
    return new ScreenContext(run.runId());
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
    ObjectNode argNode = typedArgs(run, toolId, args);
    String idem =
        run.runId()
            + "-"
            + toolId
            + "-"
            + ("step".equals(kind) ? String.valueOf(run.nextSeq()) : kind);
    ToolInvoke.Request req =
        new ToolInvoke.Request(
            toolId,
            version,
            argNode,
            new ToolInvoke.ExecutionContext(
                run.runId(), toolCallId, run.sessionId(), idem, traceId));
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
      // 过载与「工具真的执行失败」对用户的含义不同：前者稍后重试有意义，后者可能反复失败。
      // 契约的 RunFailureCode 不新增值（那会动 sse-events 与前端投影），只把用户文案分开。
      if (gw == ToolInvoke.ErrorCode.RATE_LIMITED) {
        throw RunFailure.withUserText(code.name(), "session rate limited", OVERLOADED_TEXT);
      }
      throw new ToolCallFailed(code, gw, toolId, null);
    }
    return resp.output();
  }

  /**
   * Step.fixedArgs 一律字符串（参与 argsDigest）；按 Run 里快照的 inputSchema 把 integer / boolean 类型的参数转成对应 JSON
   * 类型，其余保持字符串。
   */
  private ObjectNode typedArgs(Run run, String toolId, Map<String, String> args) {
    ObjectNode node = mapper.createObjectNode();
    JsonNode props =
        run.stepSchema(toolId)
            .map(this::readTree)
            .orElseGet(mapper::createObjectNode)
            .path("properties");
    args.forEach(
        (k, v) -> {
          String type = props.path(k).path("type").asText("string");
          try {
            switch (type) {
              case "integer" -> node.put(k, Long.parseLong(v));
              case "number" -> node.put(k, Double.parseDouble(v));
              case "boolean" -> node.put(k, Boolean.parseBoolean(v));
              default -> node.put(k, v);
            }
          } catch (NumberFormatException e) {
            node.put(k, v); // 类型不符交给 Gateway 的 inputSchema 校验报 INPUT_INVALID
          }
        });
    return node;
  }

  /** 候选工具的 inputSchema → 字符串，供 Run 按计划步骤快照。 */
  private static Map<String, String> schemaSnapshot(ToolSearch.Response found) {
    Map<String, String> m = new HashMap<>();
    found.tools().forEach(c -> m.put(c.toolId(), c.inputSchema().toString()));
    return m;
  }

  private void setUi(Run run, UiSchema ui) {
    try {
      run.setCurrentUi(mapper.writeValueAsString(ui), now());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialize UiSchema", e);
    }
  }

  private JsonNode readTree(String json) {
    try {
      return mapper.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("stored JSON is malformed", e);
    }
  }

  private UiSchema readUi(String json) {
    try {
      return mapper.readValue(json, UiSchema.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("stored currentUi is not a UiSchema", e);
    }
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
    // 终态后前置输出无人再读，清掉以减小存储；currentUi 保留（GET 需要）
    run.clearStepOutputs();
    // 先落终态再写记忆：记忆是锦上添花，它抛异常不该让共享存储里的 Run 停在 EXECUTING（另一副本会看到一个永远"执行中"的 Run）
    runs.save(run);
    try {
      remember(run);
    } catch (RuntimeException e) {
      log.warn("memory_write_failed runId={}", run.runId(), e);
    }
    runs.evictExpired();
    emit(sink, SseEvent.RUN_COMPLETED, new SseEvent.RunCompletedData(run.runId(), now()));
    recordRunMetrics(run, "completed");
    sink.close();
  }

  private void fail(Run run, RunFailure e, RunEventSink sink) {
    log.warn("run failed runId={} code={} reason={}", run.runId(), e.code(), e.getMessage());
    recordRunMetrics(run, e.code());
    if (!run.state().terminal()) {
      run.fail(e.code(), now());
    }
    run.clearStepOutputs();
    runs.save(run);
    runs.evictExpired();
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

  /**
   * 写 Run 埋点，**失败不影响对话**（评审 S-2 同一手法）。
   *
   * <p>覆盖三个出口：{@code complete()} / {@code fail()} / {@code rejectRequest()}。漏任一个，
   * 成功率指标就失真——而"看起来有监控但数字是错的"比没监控更危险（评审 S-1）。
   *
   * <p>steps 取计划步数；无计划（澄清屏 / 无能力 / 规划失败）时为 0。
   */
  private void recordRunMetrics(Run run, String outcome) {
    try {
      long ms = Duration.between(run.createdAt(), now()).toMillis();
      int steps = run.plan().map(p -> p.steps().size()).orElse(0);
      runMetrics.record(new RunMetricsSink.Sample(outcome, ms, steps));
    } catch (RuntimeException e) {
      log.warn("run_metrics_failed runId={} outcome={}", run.runId(), outcome, e);
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
