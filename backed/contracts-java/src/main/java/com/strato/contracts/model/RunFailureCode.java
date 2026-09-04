package com.strato.contracts.model;

/** SSE run.failed.data.code 与 run-summary.failureCode 共用的枚举。 */
public enum RunFailureCode {
  CONFIRMATION_REJECTED,
  TOOL_SELECTION_INVALID,
  TOOL_OUTPUT_INVALID,
  TOOL_EXECUTION_FAILED,
  INTERNAL_ERROR
}
