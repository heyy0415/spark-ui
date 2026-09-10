package com.sparkrooter.spi;

/**
 * 宿主上下文跨线程传播。Runtime 在 agent-run-* 线程规划与编排，Gateway 在 tool-* 线程调用工具方法，都不是宿主的请求线程； 宿主 ThreadLocal /
 * SecurityContextHolder / MDC 在那里为空。web 层接到请求时 {@link #capture}，每次切线程前 {@link #restore}、结束后 {@link
 * #clear}（finally）。默认 no-op 并在启动时 WARN。
 */
public interface RunContextPropagator {

  /** 在请求线程捕获宿主上下文；返回值不透明，只回传给 restore。 */
  Object capture();

  /** 在工作线程恢复。 */
  void restore(Object captured);

  /** 在工作线程清理，防止线程复用泄漏。 */
  void clear();
}
