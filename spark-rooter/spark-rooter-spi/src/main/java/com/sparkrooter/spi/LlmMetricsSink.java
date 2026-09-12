package com.sparkrooter.spi;

/**
 * LLM 调用的埋点端口（宿主可替换，与 {@link AuditSink} 同风格）。
 *
 * <p>每次规划调用恰写一条，含失败与熔断跳过的情况。默认实现落日志；宿主要接 Micrometer / Prometheus 自行定义同类型 Bean 即覆盖。
 *
 * <p>本端口只覆盖 LLM 一处——它是真实花钱的地方。Run / 工具 / 确认三个维度的指标未纳入，需要时另加。
 */
public interface LlmMetricsSink {

  /**
   * 一次规划调用的观测值。
   *
   * @param outcome 结果类别：planned / clarify / no_capability / invalid_output / transport_error /
   *     circuit_open
   * @param durationMs 从进入 plan() 到返回或抛出的耗时
   * @param promptTokens 输入 token；**上游网关不返回 usage 时为 null**，所以用包装类型而非 int（0 会被误读成「没消耗」）
   * @param completionTokens 输出 token，同上
   * @param attempts 实际尝试次数（校验失败会把原因喂回模型重试，最多 2 次）
   * @param circuitOpen 本次是否因熔断打开而跳过了真实调用
   */
  record Sample(
      String outcome,
      long durationMs,
      Integer promptTokens,
      Integer completionTokens,
      int attempts,
      boolean circuitOpen) {}

  void record(Sample sample);
}
