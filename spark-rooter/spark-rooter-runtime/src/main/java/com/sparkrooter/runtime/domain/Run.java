package com.sparkrooter.runtime.domain;

import com.sparkrooter.spi.Principal;
import java.time.Instant;
import java.util.Optional;

/** Run 聚合：一次 Agent 任务的生命周期。状态迁移幂等（同一迁移重放不产生副作用）。 计划、当前步骤、失败码都只存后端；前端只拿 runId 与 UI Schema。 */
public final class Run {

  private final String runId;
  private final String conversationId;
  private final Principal principal;
  private final String message;
  private final Instant createdAt;
  private RunState state = RunState.CREATED;
  private Plan plan;
  private int nextSeq = 1;
  private String failureCode;
  private Instant updatedAt;

  public Run(
      String runId, String conversationId, Principal principal, String message, Instant now) {
    this.runId = runId;
    this.conversationId = conversationId;
    this.principal = principal;
    this.message = message;
    this.createdAt = now;
    this.updatedAt = now;
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

  public void attachPlan(Plan p, Instant now) {
    if (plan != null) {
      throw new IllegalStateException("plan already attached to " + runId);
    }
    this.plan = p;
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

  public Principal principal() {
    return principal;
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
}
