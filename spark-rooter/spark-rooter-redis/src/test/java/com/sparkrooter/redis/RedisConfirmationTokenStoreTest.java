package com.sparkrooter.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparkrooter.contracts.PlatformMapper;
import com.sparkrooter.runtime.domain.ConfirmationToken;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 令牌表：一次性（GETDEL）、TTL 跟随 expiresAt、并发消费恰好一个成功。
 *
 * <p>最后一条是 agent-safety §3 的前提——编排器删掉了实例内的确认锁，互斥完全押在这条原子性上。
 */
@EnabledIf("com.sparkrooter.redis.RedisTestBase#redisAvailable")
final class RedisConfirmationTokenStoreTest extends RedisTestBase {

  private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");

  private final StringRedisTemplate redis = template();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final RedisConfirmationTokenStore store =
      new RedisConfirmationTokenStore(redis, PlatformMapper.create(), clock);

  private ConfirmationToken token(String id, Instant expiresAt) {
    return new ConfirmationToken(
        id, "run_1", "confirm", 1, "digest", "conv", "sess", Set.of("reason"), expiresAt);
  }

  @Test
  void consumeIsOneShotAndRoundTripsEveryField() {
    String id = "ct_" + scope();
    ConfirmationToken t = token(id, NOW.plusSeconds(600));
    store.put(t);

    assertThat(store.consume(id)).contains(t);
    assertThat(store.consume(id)).as("第二次 consume 必须为空").isEmpty();
    assertThat(store.consume("ct_never")).isEmpty();
  }

  @Test
  void ttlFollowsExpiresAt() {
    String id = "ct_" + scope();
    store.put(token(id, NOW.plusSeconds(120)));
    Long ttl = redis.getExpire(RedisConfirmationTokenStore.PREFIX + id, TimeUnit.SECONDS);
    assertThat(ttl).isNotNull().isBetween(100L, 120L);
    store.consume(id);
  }

  @Test
  void alreadyExpiredTokenIsNotStored() {
    String id = "ct_" + scope();
    store.put(token(id, NOW.minusSeconds(1)));
    assertThat(redis.hasKey(RedisConfirmationTokenStore.PREFIX + id)).isFalse();
    assertThat(store.consume(id)).isEmpty();
  }

  /** 32 线程同时消费同一令牌：恰好 1 个拿到。GETDEL 原子性就是确认路径唯一的互斥。 */
  @Test
  void concurrentConsumeSucceedsExactlyOnce() throws Exception {
    String id = "ct_" + scope();
    store.put(token(id, NOW.plusSeconds(600)));
    int threads = 32;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger got = new AtomicInteger();
    try {
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              try {
                start.await();
                if (store.consume(id).isPresent()) {
                  got.incrementAndGet();
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
    assertThat(got).as("恰好一个消费成功").hasValue(1);
  }
}
