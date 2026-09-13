package com.sparkrooter.runtime.application.port;

import com.sparkrooter.contracts.model.SseEvent;

/** 事件出口端口；SSE 实现在 api 层。事件在发出前已按 sse-events 契约校验。 */
public interface RunEventSink {
  void emit(SseEvent event);

  /** 终态或等待确认时关闭当前连接。 */
  void close();

  /**
   * 连接是否已不可用（客户端断开 / 超时 / 已 close）。
   *
   * <p>编排器据此在只读步骤前提前终止——没人在看的结果不值得再调一次工具。默认 false 让录制型 / 非流式实现无需关心。
   */
  default boolean isClosed() {
    return false;
  }
}
