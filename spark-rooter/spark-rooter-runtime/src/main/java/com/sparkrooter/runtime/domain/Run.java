package com.sparkrooter.runtime.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Run 聚合：一次 Agent 任务的生命周期。状态迁移幂等（同一迁移重放不产生副作用）。 计划、当前步骤、失败码都只存后端；前端只拿 runId 与 UI Schema。
 *
 * <p><b>Run 自包含</b>：确认请求可能落到另一个 hub 副本，那个副本必须只凭 {@code RunRepository} 里的这一条记录就能完成确认——
 * 所以最近一次下发的屏、前置步骤的输出、计划各步骤的 inputSchema 都随 Run 走，而不是留在编排器的进程内缓存里。 三者以 JSON 字符串形态存放：{@code domain/}
 * 包不依赖 Jackson（后端红线），转换在编排器完成。
 */
public final class Run {

  private final String runId;
  private final String conversationId;
  private final String sessionId;
  private final String message;
  private final Instant createdAt;
  private RunState state = RunState.CREATED;
  private Plan plan;
  private int nextSeq = 1;
  private String failureCode;
  private Instant updatedAt;

  /** 最近一次下发的 UI Schema（JSON 字符串），供 GET /agent/runs/{id} 与确认时的重校验比对。 */
  private String currentUi;

  /** 前置只读步骤的输出（toolId → JSON 字符串），确认屏与重校验读它；终态后清空（只服务确认路径）。 */
  private final Map<String, String> stepOutputs = new LinkedHashMap<>();

  /** 计划各步骤的 inputSchema 快照（toolId → JSON 字符串），参数类型化用；attachPlan 时写入。 */
  private final Map<String, String> stepSchemas = new LinkedHashMap<>();

  /** 本 Run 已出澄清屏：记忆已由澄清路径写入，终态不再覆盖。 */
  private boolean clarified;

  public Run(String runId, String conversationId, String sessionId, String message, Instant now) {
    this.runId = runId;
    this.conversationId = conversationId;
    this.sessionId = sessionId;
    this.message = message;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /**
   * 从持久化快照重建（供共享存储实现反序列化用；不走构造器的"新建"语义，也不校验迁移合法性——快照里的状态就是事实）。
   *
   * @param stepOutputs 可为 null
   * @param stepSchemas 可为 null
   */
  public static Run restore(
      String runId,
      String conversationId,
      String sessionId,
      String message,
      Instant createdAt,
      Instant updatedAt,
      RunState state,
      Plan plan,
      int nextSeq,
      String failureCode,
      String currentUi,
      Map<String, String> stepOutputs,
      Map<String, String> stepSchemas,
      boolean clarified) {
    Run r = new Run(runId, conversationId, sessionId, message, createdAt);
    r.updatedAt = updatedAt;
    r.state = state;
    r.plan = plan;
    r.nextSeq = nextSeq;
    r.failureCode = failureCode;
    r.currentUi = currentUi;
    if (stepOutputs != null) {
      r.stepOutputs.putAll(stepOutputs);
    }
    if (stepSchemas != null) {
      r.stepSchemas.putAll(stepSchemas);
    }
    r.clarified = clarified;
    return r;
  }

  /** 幂等迁移：非法迁移抛异常，重复迁移 no-op。 */
  public void transition(RunState next, Instant now) {
    if (!state.canTransitionTo(next)) {
      throw new IllegalStateException(
          "illegal transition " + state + " -> " + next + " for " + runId);
    }
    if (state != next) {
      state = next;
      updatedAt = now;
    }
  }

  /** 不带 schema 快照的附加（测试与不需要类型化参数的调用方）。 */
  public void attachPlan(Plan p, Instant now) {
    attachPlan(p, Map.of(), now);
  }

  /**
   * 附加计划，同时快照各步骤的 inputSchema。
   *
   * @param schemas toolId → inputSchema JSON 字符串；只保留计划里出现的工具，其余丢弃
   */
  public void attachPlan(Plan p, Map<String, String> schemas, Instant now) {
    if (plan != null) {
      throw new IllegalStateException("plan already attached to " + runId);
    }
    this.plan = p;
    for (Step s : p.steps()) {
      String schema = schemas.get(s.toolId());
      if (schema != null) {
        stepSchemas.put(s.toolId(), schema);
      }
    }
    this.updatedAt = now;
  }

  public Optional<Step> currentStep() {
    if (plan == null || nextSeq > plan.steps().size()) {
      return Optional.empty();
    }
    return Optional.of(plan.step(nextSeq));
  }

  public void advance(Instant now) {
    nextSeq++;
    updatedAt = now;
  }

  public void fail(String code, Instant now) {
    transition(RunState.FAILED, now);
    this.failureCode = code;
  }

  public void setCurrentUi(String uiJson, Instant now) {
    this.currentUi = uiJson;
    this.updatedAt = now;
  }

  public void putStepOutput(String toolId, String outputJson, Instant now) {
    stepOutputs.put(toolId, outputJson);
    this.updatedAt = now;
  }

  /** 终态后前置输出无人再读，清掉以减小存储；currentUi 保留（GET 需要）。 */
  public void clearStepOutputs() {
    stepOutputs.clear();
  }

  public void markClarified(Instant now) {
    this.clarified = true;
    this.updatedAt = now;
  }

  /** idempotencyKey = {runId}-{toolId}-{seq}（domain-model.md）。 */
  public String idempotencyKeyFor(Step s) {
    return runId + "-" + s.toolId() + "-" + s.seq();
  }

  public String runId() {
    return runId;
  }

  public String conversationId() {
    return conversationId;
  }

  /** 宿主 SessionIdResolver 产出的会话键；Run 查询与确认都按它隔离。 */
  public String sessionId() {
    return sessionId;
  }

  public String message() {
    return message;
  }

  public RunState state() {
    return state;
  }

  public Optional<Plan> plan() {
    return Optional.ofNullable(plan);
  }

  public int nextSeq() {
    return nextSeq;
  }

  public Optional<String> failureCode() {
    return Optional.ofNullable(failureCode);
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public Optional<String> currentUi() {
    return Optional.ofNullable(currentUi);
  }

  /** 只读视图。 */
  public Map<String, String> stepOutputs() {
    return Collections.unmodifiableMap(stepOutputs);
  }

  public Optional<String> stepOutput(String toolId) {
    return Optional.ofNullable(stepOutputs.get(toolId));
  }

  /** 只读视图。 */
  public Map<String, String> stepSchemas() {
    return Collections.unmodifiableMap(stepSchemas);
  }

  public Optional<String> stepSchema(String toolId) {
    return Optional.ofNullable(stepSchemas.get(toolId));
  }

  public boolean clarified() {
    return clarified;
  }
}
