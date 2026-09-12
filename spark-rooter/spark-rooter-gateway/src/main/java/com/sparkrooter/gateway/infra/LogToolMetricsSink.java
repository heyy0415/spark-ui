package com.sparkrooter.gateway.infra;

import com.sparkrooter.spi.ToolMetricsSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具埋点落日志（默认实现，宿主定义 {@link ToolMetricsSink} Bean 即替换）。
 *
 * <p>一行 key=value 便于 grep 与聚合，与 {@code LLM_METRICS} 同风格。errorCode 为 null 时输出 {@code
 * -}（成功路径），不写空串——空串在 grep 结果里难以区分。
 */
public class LogToolMetricsSink implements ToolMetricsSink {

  private static final Logger metrics = LoggerFactory.getLogger("TOOL_METRICS");

  @Override
  public void record(Sample s) {
    metrics.info(
        "tool toolId={} status={} errorCode={} durationMs={}",
        s.toolId(),
        s.status(),
        s.errorCode() == null ? "-" : s.errorCode(),
        s.durationMs());
  }
}
