package com.sparkrooter.gateway.infra;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 工具调用线程池（禁止显式 new Thread；超时由 CompletableFuture.get 控制）。 */
@Configuration
public class GatewayExecutorConfiguration {

  private static final int POOL_SIZE = 16;

  @Bean(destroyMethod = "shutdown")
  public ExecutorService toolExecutor() {
    ThreadFactory tf =
        new ThreadFactory() {
          private final AtomicInteger n = new AtomicInteger();

          @Override
          public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "gateway-tool-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
          }
        };
    return Executors.newFixedThreadPool(POOL_SIZE, tf);
  }
}
