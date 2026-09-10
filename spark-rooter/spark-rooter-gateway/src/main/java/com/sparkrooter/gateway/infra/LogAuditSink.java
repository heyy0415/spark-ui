package com.sparkrooter.gateway.infra;

import com.sparkrooter.spi.AuditSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 审计落日志（默认实现，宿主定义 AuditSink Bean 即替换）。一行 key=value，9 字段，便于 grep；不含参数与输出原文。 */
public class LogAuditSink implements AuditSink {

  private static final Logger audit = LoggerFactory.getLogger("AUDIT");

  @Override
  public void record(Entry e) {
    audit.info(
        "audit runId={} toolCallId={} toolId={} version={} sessionId={} argsDigest={} status={} durationMs={} traceId={}",
        e.runId(),
        e.toolCallId(),
        e.toolId(),
        e.version(),
        e.sessionId(),
        e.argsDigest(),
        e.status(),
        e.durationMs(),
        e.traceId());
  }
}
