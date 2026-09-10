package com.sparkrooter.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** 契约 run-summary：GET /agent/runs/{runId} 响应。不含计划、参数、模型输出。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunSummary(
    String runId,
    String conversationId,
    RunState state,
    UiSchema currentUi,
    RunFailureCode failureCode,
    Instant createdAt,
    Instant updatedAt) {

  /** Run 状态机的六个状态。 */
  public enum RunState {
    CREATED,
    PLANNING,
    EXECUTING,
    WAITING_CONFIRMATION,
    COMPLETED,
    FAILED
  }
}
