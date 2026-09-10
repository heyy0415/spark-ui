package com.sparkrooter.runtime.api;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runtime 线程池：Run 编排线程（禁止显式 new Thread）。SSE ping 调度线程随 SSE 端点在 web-mvc。 */
@Configuration
public class RuntimeExecutorConfiguration {

  private static final int RUN_POOL = 8;

  @Bean(destroyMethod = "shutdown")
  public ExecutorService runExecutor() {
    return Executors.newFixedThreadPool(RUN_POOL, named("agent-run-"));
  }

  private static ThreadFactory named(String prefix) {
    return new ThreadFactory() {
      private final AtomicInteger n = new AtomicInteger();

      @Override
      public Thread newThread(Runnable r) {
        Thread t = new Thread(r, prefix + n.incrementAndGet());
        t.setDaemon(true);
        return t;
      }
    };
  }
}
