package com.sparkrooter.starter;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 平台线程池的命名守护线程工厂与有界池构造（禁止显式 new Thread 散落在业务代码、禁止用 Executors 工厂）。 */
final class NamedThreads {

  private NamedThreads() {}

  /**
   * 有界线程池：固定 core 线程 + 容量受限的队列 + AbortPolicy。
   *
   * <p>不用 JDK 的固定大小池工厂方法——它内部的 {@code LinkedBlockingQueue} 无界，过载时任务无声堆积 直到
   * OOM，用户侧表现为连接挂着既不失败也不返回。队列满时 {@code submit} 抛 {@link
   * java.util.concurrent.RejectedExecutionException}，由调用方转成用户可见的失败。
   *
   * @param core 核心线程数，同时也是最大线程数（固定大小池）
   * @param queueCapacity 队列容量，满则拒绝
   */
  static ThreadPoolExecutor boundedPool(int core, int queueCapacity, String prefix) {
    return new ThreadPoolExecutor(
        core,
        core,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(queueCapacity),
        named(prefix),
        new ThreadPoolExecutor.AbortPolicy());
  }

  static ThreadFactory named(String prefix) {
    AtomicInteger n = new AtomicInteger();
    return r -> {
      Thread t = new Thread(r, prefix + n.incrementAndGet());
      t.setDaemon(true);
      return t;
    };
  }
}
