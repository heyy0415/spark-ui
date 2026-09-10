package com.sparkrooter.gateway.infra;

import com.sparkrooter.gateway.domain.AuditSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 审计落日志（首期）。一行 key=value，9 字段，便于 grep；不含参数与输出原文。 */
@Component
public class LogAuditSink implements AuditSink {

  private static final Logger audit = LoggerFactory.getLogger("AUDIT");

  @Override
  public void record(Entry e) {
    audit.info(
        "audit runId={} toolCallId={} toolId={} version={} principal={} argsDigest={} status={} durationMs={} traceId={}",
        e.runId(),
        e.toolCallId(),
        e.toolId(),
        e.version(),
        e.principal(),
        e.argsDigest(),
        e.status(),
        e.durationMs(),
        e.traceId());
  }
}
