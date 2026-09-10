package com.sparkrooter.runtime.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkrooter.contracts.model.SseEvent;
import com.sparkrooter.runtime.application.port.RunEventSink;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RunEventSink 的 SSE 实现。每帧 event: 名 + data: JSON；每 15s 发注释帧 ": ping"（spec §4.0）。 close() 幂等；连接断开后
 * emit 静默丢弃并记 debug。
 */
final class SseRunEventSink implements RunEventSink {

  private static final Logger log = LoggerFactory.getLogger(SseRunEventSink.class);
  static final long PING_SECONDS = 15;

  private final SseEmitter emitter;
  private final ObjectMapper mapper;
  private final ScheduledFuture<?> ping;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  SseRunEventSink(SseEmitter emitter, ObjectMapper mapper, ScheduledExecutorService scheduler) {
    this.emitter = emitter;
    this.mapper = mapper;
    this.ping =
        scheduler.scheduleAtFixedRate(
            () -> {
              if (closed.get()) {
                return;
              }
              try {
                emitter.send(SseEmitter.event().comment("ping"));
              } catch (IOException | IllegalStateException e) {
                close();
              }
            },
            PING_SECONDS,
            PING_SECONDS,
            TimeUnit.SECONDS);
    emitter.onCompletion(this::close);
    emitter.onTimeout(this::close);
    emitter.onError(t -> close());
  }

  @Override
  public void emit(SseEvent event) {
    if (closed.get()) {
      return;
    }
    try {
      emitter.send(
          SseEmitter.event().name(event.event()).data(mapper.writeValueAsString(event.data())));
    } catch (IOException | IllegalStateException e) {
      log.debug("sse client gone, dropping event {}", event.event());
      close();
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      ping.cancel(false);
      try {
        emitter.complete();
      } catch (IllegalStateException ignored) {
        // already completed by container
      }
    }
  }
}
