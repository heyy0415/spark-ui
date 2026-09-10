package com.sparkrooter.runtime.application.port;

import com.sparkrooter.contracts.model.SseEvent;

/** 事件出口端口；SSE 实现在 api 层。事件在发出前已按 sse-events 契约校验。 */
public interface RunEventSink {
  void emit(SseEvent event);

  /** 终态或等待确认时关闭当前连接。 */
  void close();
}
