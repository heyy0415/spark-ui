package com.sparkrooter.runtime.infra;

import com.sparkrooter.spi.RunMetricsSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Run 埋点落日志（默认实现，宿主定义 {@link RunMetricsSink} Bean 即替换）。
 *
 * <p>一行 key=value，与 {@code LLM_METRICS} / {@code TOOL_METRICS} 同风格。
 */
public class LogRunMetricsSink implements RunMetricsSink {

  private static final Logger metrics = LoggerFactory.getLogger("RUN_METRICS");

  @Override
  public void record(Sample s) {
    metrics.info("run outcome={} durationMs={} steps={}", s.outcome(), s.durationMs(), s.steps());
  }
}
