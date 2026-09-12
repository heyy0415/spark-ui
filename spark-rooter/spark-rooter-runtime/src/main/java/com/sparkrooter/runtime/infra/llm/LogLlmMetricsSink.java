package com.sparkrooter.runtime.infra.llm;

import com.sparkrooter.spi.LlmMetricsSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 埋点落日志（默认实现，宿主定义 LlmMetricsSink Bean 即替换）。一行 key=value 便于 grep 与聚合。
 *
 * <p>token 为 null 时输出 {@code -} 而不是 0——上游网关不返回 usage 是常见情况，写 0 会被误读成「没消耗」。
 */
public class LogLlmMetricsSink implements LlmMetricsSink {

  private static final Logger metrics = LoggerFactory.getLogger("LLM_METRICS");

  @Override
  public void record(Sample s) {
    metrics.info(
        "llm outcome={} durationMs={} promptTokens={} completionTokens={} attempts={} circuitOpen={}",
        s.outcome(),
        s.durationMs(),
        orDash(s.promptTokens()),
        orDash(s.completionTokens()),
        s.attempts(),
        s.circuitOpen());
  }

  private static String orDash(Integer v) {
    return v == null ? "-" : v.toString();
  }
}
