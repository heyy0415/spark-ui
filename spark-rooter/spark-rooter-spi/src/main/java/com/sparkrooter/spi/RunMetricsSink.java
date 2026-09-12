package com.sparkrooter.spi;

/**
 * 一轮对话（Run）的埋点端口（与 {@link LlmMetricsSink} / {@link ToolMetricsSink} 同风格）。
 *
 * <p>观测的是**用户视角的成败**：一次提问最终有没有得到结果。工具级与 LLM 级指标看不出这个—— 每个工具都成功但规划失败，用户依然没拿到答案。
 *
 * <p>默认实现落日志；宿主要接 Micrometer / Prometheus 定义同类型 Bean 即覆盖。
 *
 * <p><b>实现不应抛异常</b>：Runtime 会捕获并记 WARN，不影响对话结果。
 */
public interface RunMetricsSink {

  /**
   * 一轮 Run 的观测值。
   *
   * <p>不含 runId / conversationId / sessionId（高基数，见 {@link ToolMetricsSink.Sample}）。
   *
   * @param outcome 出口类别：completed（正常完成）/ 各 RunFailureCode / rejected（过载拒绝）
   * @param durationMs 从收到请求到 run.completed 或 run.failed 的耗时
   * @param steps 计划步数；0 表示未产出计划（澄清屏 / 无能力 / 规划失败）
   */
  record Sample(String outcome, long durationMs, int steps) {}

  void record(Sample sample);
}
