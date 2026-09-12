package com.sparkrooter.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 有界线程池：队列满即拒绝，不静默堆积。
 *
 * <p>这是本 change 的核心不变式——原来用 JDK 固定大小池工厂方法，队列无界，过载时任务堆到 OOM，用户侧表现为连接挂着 既不失败也不返回。
 */
final class BoundedPoolTest {

  /** 用 latch 占住唯一的工作线程，使后续任务确定落在队列里，而不是靠调度巧合。 */
  @Test
  void rejectsWhenQueueIsFull() throws InterruptedException {
    ThreadPoolExecutor pool = NamedThreads.boundedPool(1, 1, "test-bounded-");
    CountDownLatch blockWorker = new CountDownLatch(1);
    CountDownLatch workerStarted = new CountDownLatch(1);
    try {
      // 任务 1：占住唯一线程
      pool.submit(
          () -> {
            workerStarted.countDown();
            try {
              blockWorker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();

      // 任务 2：填满容量为 1 的队列
      pool.submit(() -> {});
      assertThat(pool.getQueue()).hasSize(1);

      // 任务 3：无线程可用、队列已满 → AbortPolicy 抛异常
      assertThatThrownBy(() -> pool.submit(() -> {}))
          .isInstanceOf(RejectedExecutionException.class);
    } finally {
      blockWorker.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void usesGivenThreadNamePrefixAndDaemonThreads() throws InterruptedException {
    ThreadPoolExecutor pool = NamedThreads.boundedPool(1, 1, "spark-probe-");
    AtomicReference<Thread> seen = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    try {
      pool.submit(
          () -> {
            seen.set(Thread.currentThread());
            done.countDown();
          });
      assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(seen.get().getName()).startsWith("spark-probe-");
      // 守护线程：JVM 退出不被工作线程拖住
      assertThat(seen.get().isDaemon()).isTrue();
    } finally {
      pool.shutdownNow();
    }
  }

  /** 容量之内不应拒绝——防止把「有界」写成「过窄」而误伤正常流量。 */
  @Test
  void acceptsUpToCapacity() throws InterruptedException {
    ThreadPoolExecutor pool = NamedThreads.boundedPool(2, 4, "test-capacity-");
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch bothRunning = new CountDownLatch(2);
    try {
      // 2 个线程 + 4 个队列位 = 6 个任务全部应被接受
      for (int i = 0; i < 2; i++) {
        pool.submit(
            () -> {
              bothRunning.countDown();
              try {
                release.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
      }
      assertThat(bothRunning.await(5, TimeUnit.SECONDS)).isTrue();
      for (int i = 0; i < 4; i++) {
        pool.submit(() -> {});
      }
      assertThat(pool.getQueue()).hasSize(4);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }
}
