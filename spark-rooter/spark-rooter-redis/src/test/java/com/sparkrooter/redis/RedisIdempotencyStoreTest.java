package com.sparkrooter.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparkrooter.contracts.PlatformMapper;
import com.sparkrooter.contracts.model.SseEvent;
import com.sparkrooter.contracts.model.ToolInvoke;
import com.sparkrooter.gateway.domain.IdempotencyStore.Claim;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 幂等表：与内存实现同一套语义断言（占位 / 等待 / 重放 / 释放），外加两条 Redis 特有的：owner 消失后等待方重 claim； 32 线程并发 claim 恰好一个
 * Owner。
 *
 * <p>本机没有 Redis 时整类跳过（surefire 计 skipped，不计 passed）。
 */
@EnabledIf("com.sparkrooter.redis.RedisTestBase#redisAvailable")
final class RedisIdempotencyStoreTest extends RedisTestBase {

  private final StringRedisTemplate redis = template();
  private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
  private final ExecutorService workers = Executors.newFixedThreadPool(4);
  private final String scope = scope();

  private RedisIdempotencyStore store(Duration claimTtl) {
    return new RedisIdempotencyStore(redis, PlatformMapper.create(), claimTtl, SHORT, poller);
  }

  @AfterEach
  void shutdown() {
    poller.shutdownNow();
    workers.shutdownNow();
  }

  @Test
  void ownerThenAwaitingReceiveSameResultThenReplay() throws Exception {
    RedisIdempotencyStore s = store(SHORT);
    String key = "k1";
    assertThat(s.claim(scope, key)).isInstanceOf(Claim.Owner.class);

    Claim second = s.claim(scope, key);
    assertThat(second).isInstanceOf(Claim.Awaiting.class);
    CompletableFuture<ToolInvoke.Response> f = ((Claim.Awaiting) second).future();

    s.complete(scope, key, response("tc_a"));
    assertThat(f.get(2, TimeUnit.SECONDS).toolCallId()).isEqualTo("tc_a");
    assertThat(s.claim(scope, key)).isInstanceOf(Claim.Replay.class);
    assertThat(s.find(scope, key)).map(ToolInvoke.Response::toolCallId).contains("tc_a");
  }

  @Test
  void releaseWakesWaiterExceptionallyAndAllowsReclaim() throws Exception {
    RedisIdempotencyStore s = store(SHORT);
    String key = "k2";
    assertThat(s.claim(scope, key)).isInstanceOf(Claim.Owner.class);
    CompletableFuture<ToolInvoke.Response> f = ((Claim.Awaiting) s.claim(scope, key)).future();

    s.release(scope, key);

    // 轮询周期 50ms，给足余量
    Thread.sleep(200);
    assertThat(f.isCompletedExceptionally()).isTrue();
    assertThat(s.claim(scope, key)).isInstanceOf(Claim.Owner.class);
    assertThat(s.find(scope, key)).isEmpty();
  }

  @Test
  void releaseAfterCompleteIsNoop() {
    RedisIdempotencyStore s = store(SHORT);
    String key = "k3";
    s.claim(scope, key);
    s.complete(scope, key, response("tc_c"));
    s.release(scope, key);
    assertThat(s.find(scope, key)).isPresent();
    assertThat(s.claim(scope, key)).isInstanceOf(Claim.Replay.class);
  }

  @Test
  void scopeIsolatesKeys() {
    RedisIdempotencyStore s = store(SHORT);
    assertThat(s.claim(scope + "-a", "same")).isInstanceOf(Claim.Owner.class);
    assertThat(s.claim(scope + "-b", "same")).isInstanceOf(Claim.Owner.class);
  }

  @Test
  void findIsEmptyWhileClaimedOrUnknown() {
    RedisIdempotencyStore s = store(SHORT);
    assertThat(s.find(scope, "unknown")).isEmpty();
    s.claim(scope, "held");
    assertThat(s.find(scope, "held")).isEmpty();
  }

  /**
   * owner 崩溃（没 complete 也没 release）：占位 key 到期消失，等待方轮询看到 nil → 异常完成 → 重 claim 成为 Owner。
   *
   * <p>这是 Redis 版必须补的一条：内存版靠 release 显式唤醒，Redis 版没有 release 时只能靠 TTL。
   */
  @Test
  void waiterReclaimsAfterOwnerVanishesAndClaimExpires() throws Exception {
    RedisIdempotencyStore s = store(Duration.ofMillis(300));
    String key = "k-vanish";
    assertThat(s.claim(scope, key)).isInstanceOf(Claim.Owner.class);
    CompletableFuture<ToolInvoke.Response> f = ((Claim.Awaiting) s.claim(scope, key)).future();

    // 不 complete、不 release：模拟 owner 进程消失
    Thread.sleep(600);

    assertThat(f.isCompletedExceptionally()).as("key 过期后等待方应被异常唤醒").isTrue();
    assertThat(s.claim(scope, key)).as("等待方重 claim 应成为 Owner").isInstanceOf(Claim.Owner.class);
  }

  /** 32 线程同时 claim 同一 key：恰好 1 个 Owner，其余全是 Awaiting。SET NX 的原子性就是这条的全部依据。 */
  @Test
  void concurrentClaimGrantsExactlyOneOwner() throws Exception {
    RedisIdempotencyStore s = store(SHORT);
    String key = "k-race";
    int threads = 32;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger owners = new AtomicInteger();
    AtomicInteger awaiting = new AtomicInteger();
    try {
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              try {
                start.await();
                Claim c = s.claim(scope, key);
                if (c instanceof Claim.Owner) {
                  owners.incrementAndGet();
                } else if (c instanceof Claim.Awaiting) {
                  awaiting.incrementAndGet();
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }
    assertThat(owners).hasValue(1);
    assertThat(awaiting).hasValue(threads - 1);
    s.release(scope, key);
  }

  /** 已完成结果按 resultTtl 过期：TTL 由 Redis 管，模块自身无需清扫。 */
  @Test
  void completedResultCarriesResultTtl() {
    RedisIdempotencyStore s = store(SHORT);
    String key = "k-ttl";
    s.claim(scope, key);
    s.complete(scope, key, response("tc_t"));
    Long ttl = redis.getExpire(RedisIdempotencyStore.PREFIX + scope + "/" + key, TimeUnit.SECONDS);
    assertThat(ttl).isNotNull().isBetween(1L, SHORT.toSeconds());
  }

  /**
   * 等待方放弃（cancel future）后轮询必须停：否则每个超时的等待都留一条 50ms 周期的调度任务，跑到 key 消失为止。
   *
   * <p>用 poller 的队列长度做证据：cancel 后等两个周期，队列里不该再有本 key 的任务。
   */
  @Test
  void cancelledWaiterStopsPolling() throws Exception {
    ScheduledThreadPoolExecutor localPoller = new ScheduledThreadPoolExecutor(1);
    localPoller.setRemoveOnCancelPolicy(true);
    RedisIdempotencyStore s =
        new RedisIdempotencyStore(redis, PlatformMapper.create(), SHORT, SHORT, localPoller);
    try {
      String key = "k-cancel";
      assertThat(s.claim(scope, key)).isInstanceOf(Claim.Owner.class);
      CompletableFuture<ToolInvoke.Response> f = ((Claim.Awaiting) s.claim(scope, key)).future();
      Thread.sleep(120); // 让轮询跑起来
      assertThat(localPoller.getQueue().size() + localPoller.getActiveCount())
          .as("轮询中")
          .isGreaterThanOrEqualTo(1);

      f.cancel(false);
      Thread.sleep(200); // 两个周期后，最后一次 poll 看到 isDone 退出，不再续订

      assertThat(localPoller.getQueue()).as("cancel 后不该再有调度任务").isEmpty();
      s.release(scope, key);
    } finally {
      localPoller.shutdownNow();
    }
  }

  private static ToolInvoke.Response response(String toolCallId) {
    return new ToolInvoke.Response(toolCallId, SseEvent.ToolStatus.succeeded, 0, null, null);
  }
}
