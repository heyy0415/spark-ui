package com.sparkrooter.spi;

/**
 * 一次工具调用的上下文；全部字段为 String（ID 规范）。sessionId 由宿主 SessionIdResolver 产出，仅用于审计与令牌绑定，内核不解释其含义。 traceId
 * 可空。
 */
public record ExecutionContext(
    String runId, String toolCallId, String sessionId, String idempotencyKey, String traceId) {
  public ExecutionContext {
    if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId is blank");
    if (toolCallId == null || toolCallId.isBlank())
      throw new IllegalArgumentException("toolCallId is blank");
    if (sessionId == null || sessionId.isBlank())
      throw new IllegalArgumentException("sessionId is blank");
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey is blank");
    }
  }
}
