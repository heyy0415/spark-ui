package com.strato.spi;

/** 一次工具调用的上下文；全部字段为 String（ID 规范）。traceId 可空。 */
public record ExecutionContext(
    String runId, String toolCallId, Principal principal, String idempotencyKey, String traceId) {
  public ExecutionContext {
    if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId is blank");
    if (toolCallId == null || toolCallId.isBlank())
      throw new IllegalArgumentException("toolCallId is blank");
    if (principal == null) throw new IllegalArgumentException("principal is null");
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey is blank");
    }
  }
}
