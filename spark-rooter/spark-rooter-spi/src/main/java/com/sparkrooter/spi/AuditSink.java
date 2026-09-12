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

  /**
   * 写一条审计。
   *
   * <p><b>实现不应抛异常。</b>本方法被调用的位置在「工具已执行完」与「返回结果」之间——Gateway 会 捕获抛出的 {@code RuntimeException} 并记
   * ERROR，<b>不影响工具执行结果</b>。这是刻意的取舍： 审计丢失可由那条 ERROR 日志（字段齐全）告警补账，而让已执行的操作对调用方表现为失败会导致 用户重试 →
   * 重复副作用（如重复扣款），不可逆。
   *
   * <p>故实现应自己兜住异常（本地缓冲 / 异步投递 / 降级到日志），不要依赖 Gateway 的兜底。
   *
   * <p>实现须快速返回：它在工具调用的同步路径上，阻塞会直接拖长响应。
   */
  void record(Entry entry);
}
