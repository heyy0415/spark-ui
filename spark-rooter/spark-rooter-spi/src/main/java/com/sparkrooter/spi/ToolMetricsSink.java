package com.sparkrooter.spi;

/**
 * 工具调用的埋点端口（与 {@link LlmMetricsSink} / {@link AuditSink} 同风格）。
 *
 * <p>与 {@link AuditSink} 的分工：审计是**逐次留痕**（合规、可追溯到单次调用，含 argsDigest）； 埋点是**聚合观测**（QPS / 错误率 /
 * 延迟分布，不含任何单次标识）。两者都写，用途不同。
 *
 * <p>默认实现落日志；宿主要接 Micrometer / Prometheus 定义同类型 Bean 即覆盖。
 *
 * <p><b>实现不应抛异常</b>：Gateway 会捕获并记 WARN，不影响工具执行结果——监控故障不得拖垮业务。
 */
public interface ToolMetricsSink {

  /**
   * 一次工具调用的观测值。
   *
   * <p><b>字段刻意不含 sessionId / runId / toolCallId</b>：它们是高基数标识，做成指标标签会打爆 时序库（公司规范明确禁止）。要追溯单次调用去查审计。
   *
   * @param toolId 工具 ID。有限集合（当前示例 14 个），可安全作标签
   * @param status 结果：succeeded / failed / replayed
   * @param errorCode 失败时的契约错误码，成功为 null
   * @param durationMs 从进入 Gateway 到返回的耗时（含校验、幂等、重试）
   */
  record Sample(String toolId, String status, String errorCode, long durationMs) {}

  void record(Sample sample);
}
