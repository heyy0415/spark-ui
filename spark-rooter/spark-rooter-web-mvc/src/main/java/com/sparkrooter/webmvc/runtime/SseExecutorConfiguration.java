package com.sparkrooter.webmvc.runtime;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** SSE ping 调度线程池（原 RuntimeExecutorConfiguration 的 SSE 部分，随 SSE 端点移入 web-mvc）。 */
@Configuration
public class SseExecutorConfiguration {

  private static final int PING_POOL = 2;

  @Bean(destroyMethod = "shutdown")
  public ScheduledExecutorService pingScheduler() {
    ThreadFactory tf =
        new ThreadFactory() {
          private final AtomicInteger n = new AtomicInteger();

          @Override
          public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "sse-ping-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
          }
        };
    return Executors.newScheduledThreadPool(PING_POOL, tf);
  }
}
