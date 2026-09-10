package com.sparkrooter.starter;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** 平台线程池的命名守护线程工厂（禁止显式 new Thread 散落在业务代码）。 */
final class NamedThreads {

  private NamedThreads() {}

  static ThreadFactory named(String prefix) {
    AtomicInteger n = new AtomicInteger();
    return r -> {
      Thread t = new Thread(r, prefix + n.incrementAndGet());
      t.setDaemon(true);
      return t;
    };
  }
}
