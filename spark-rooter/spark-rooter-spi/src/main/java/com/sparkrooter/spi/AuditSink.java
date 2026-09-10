package com.sparkrooter.spi;

/** 审计端口（宿主可替换）。每次工具调用恰写一条；字段见 agent-safety §5。默认实现落 AUDIT logger。 */
public interface AuditSink {
  record Entry(
      String runId,
      String toolCallId,
      String toolId,
      String version,
      String sessionId,
      String argsDigest,
      String status,
      long durationMs,
      String traceId) {}

  void record(Entry entry);
}
