package com.strato.gateway.domain;

/** 审计端口。每次工具调用恰写一行，字段集见 agent-safety §5（9 项）。 */
public interface AuditSink {
  record Entry(
      String runId,
      String toolCallId,
      String toolId,
      String version,
      String principal,
      String argsDigest,
      String status,
      long durationMs,
      String traceId) {}

  void record(Entry entry);
}
