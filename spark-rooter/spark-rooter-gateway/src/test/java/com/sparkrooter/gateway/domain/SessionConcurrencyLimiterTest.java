package com.sparkrooter.gateway.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparkrooter.gateway.domain.SessionConcurrencyLimiter.Lease;
import com.sparkrooter.gateway.domain.SessionConcurrencyLimiter.SessionBusyException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 单会话在飞上限（feat-runtime-limits-and-metrics G3）。
 *
 * <p>防的是**只读查询洪水**：写操作已被 Gateway 的幂等 claim 序列化，只读工具没有任何序列化， 单会话可用并发请求占满整池让其他用户全被拒（阶段 2 评审 M-1
 * 修正了这个威胁模型）。
 */
final class SessionConcurrencyLimiterTest {

  private static final String SESSION = "sess_1";

  @Test
  void allowsUpToTheLimit() {
    SessionConcurrencyLimiter limiter = new SessionConcurrencyLimiter(4);
    List<Lease> held = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      held.add(limiter.acquire(SESSION));
    }
    assertThat(limiter.inFlight(SESSION)).isEqualTo(4);
    held.forEach(Lease::close);
  }

  /** 第 5 个被拒（上限 4）。 */
  @Test
  void rejectsBeyondTheLimit() {
    SessionConcurrencyLimiter limiter = new SessionConcurrencyLimiter(4);
    List<Lease> held = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      held.add(limiter.acquire(SESSION));
    }

    assertThatThrownBy(() -> limiter.acquire(SESSION))
        .isInstanceOf(SessionBusyException.class)
        .hasMessageContaining("too many in-flight");

    held.forEach(Lease::close);
  }

  /** 释放后名额可再用——限流不是一次性配额。 */
  @Test
  void releasingFreesCapacity() {
    SessionConcurrencyLimiter limiter = new SessionConcurrencyLimiter(1);
    Lease first = limiter.acquire(SESSION);
    assertThatThrownBy(() -> limiter.acquire(SESSION)).isInstanceOf(SessionBusyException.class);

    first.close();

    assertThatCode(() -> limiter.acquire(SESSION).close()).doesNotThrowAnyException();
  }

  /** 不同会话互不影响——这是本类存在的意义（防的是单会话挤占，不是全局限流）。 */
  @Test
  void sessionsAreIndependent() {
    SessionConcurrencyLimiter limiter = new SessionConcurrencyLimiter(1);
    Lease a = limiter.acquire("sess_a");

    assertThatCode(() -> limiter.acquire("sess_b").close()).doesNotThrowAnyException();

    a.close();
  }

  // ---------------------------------------------------------------- 自身不泄漏

  /**
   * 计数归零后 map 不留 key。
   *
   * <p>不移除会让 map 随历史会话数无界增长——「加了限流又引入新泄漏」。
   */
  @Test
  void zeroedCountersAreRemovedFromTheMap() {
    SessionConcurrencyLimiter limiter = new SessionConcurrencyLimiter(4);
    for (int i = 0; i < 100; i++) {
      limiter.acquire("sess_" + i).close();
    }
    assertThat(limiter.trackedSessions()).as("归零的会话不该留在 map 里").isZero();
  }

  /** 持有中的会话仍被跟踪（不能提前清掉）。 */
  @Test
  void heldSessionsRemainTracked() {
    SessionConcurrencyLimiter limiter = new SessionConcurrencyLimiter(4);
    Lease held = limiter.acquire(SESSION);
    assertThat(limiter.trackedSessions()).isEqualTo(1);
    held.close();
    assertThat(limiter.trackedSessions()).isZero();
  }

  // ---------------------------------------------------------------- 边界

  /** 上限 ≤ 0 = 不限制（宿主显式关闭），且不消耗 map。 */
  @Test
  void nonPositiveLimitDisablesLimiting() {
    SessionConcurrencyLimiter limiter = new SessionConcurrencyLimiter(0);
    for (int i = 0; i < 50; i++) {
      limiter.acquire(SESSION);
    }
    assertThat(limiter.trackedSessions()).isZero();
    assertThat(limiter.inFlight(SESSION)).isZero();
  }

  /** sessionId 为 null 时不限制、不抛（内核不解释会话键含义，只做兜底）。 */
  @Test
  void nullSessionIsNotLimited() {
    SessionConcurrencyLimiter limiter = new SessionConcurrencyLimiter(1);
    assertThatCode(
            () -> {
              limiter.acquire(null).close();
              limiter.acquire(null).close();
            })
        .doesNotThrowAnyException();
  }

  // ---------------------------------------------------------------- 并发

  /**
   * 并发申请时放行数**恰好**等于上限，不多不少。
   *
   * <p>用 CAS 循环而非「先自增再回退」就是为了这条：后者在超限瞬间会让计数短暂超过上限， 另一个线程可能误判为还有名额，导致实际并发超出上限。
   */
  @Test
  void concurrentAcquireGrantsExactlyTheLimit() throws Exception {
    int limit = 4;
    int threads = 32;
    SessionConcurrencyLimiter limiter = new SessionConcurrencyLimiter(limit);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger granted = new AtomicInteger();
    List<Lease> leases = java.util.Collections.synchronizedList(new ArrayList<>());

    try {
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              try {
                start.await();
                leases.add(limiter.acquire(SESSION));
                granted.incrementAndGet();
              } catch (SessionBusyException e) {
                // 预期：超限的线程被拒
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

      assertThat(granted).as("放行数必须恰好等于上限").hasValue(limit);
      assertThat(limiter.inFlight(SESSION)).isEqualTo(limit);
    } finally {
      leases.forEach(Lease::close);
      pool.shutdownNow();
    }
  }
}
