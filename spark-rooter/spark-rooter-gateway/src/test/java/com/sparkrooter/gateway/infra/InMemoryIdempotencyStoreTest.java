package com.sparkrooter.gateway.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparkrooter.contracts.model.SseEvent;
import com.sparkrooter.contracts.model.ToolInvoke;
import com.sparkrooter.gateway.domain.IdempotencyStore.Claim;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 内存幂等表（原 GatewayIdempotencySelfCheck 三条断言的可重复版本）：占位 / 等待 / 重放 / 释放语义。 */
final class InMemoryIdempotencyStoreTest {

  private static final String SCOPE = "sess";
  private static final long WAIT_MS = 2000;

  private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
  private final ExecutorService executor = Executors.newFixedThreadPool(2);

  @AfterEach
  void shutdown() {
    executor.shutdownNow();
  }

  @Test
  void ownerThenAwaitingReceiveSameResultThenReplay() throws Exception {
    String key = "k1";
    ToolInvoke.Response result = response("tc_a");
    assertThat(store.claim(SCOPE, key)).isInstanceOf(Claim.Owner.class);

    // 用 latch 保证 B 的 claim 确定落在 A 占位期内，而不是靠调度巧合
    CountDownLatch bClaimed = new CountDownLatch(1);
    Future<ToolInvoke.Response> b =
        executor.submit(
            () -> {
              Claim c = store.claim(SCOPE, key);
              bClaimed.countDown();
              assertThat(c).isInstanceOf(Claim.Awaiting.class);
              return ((Claim.Awaiting) c).future().get(WAIT_MS, TimeUnit.MILLISECONDS);
            });
    assertThat(bClaimed.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();

    store.complete(SCOPE, key, result);
    assertThat(b.get(WAIT_MS, TimeUnit.MILLISECONDS).toolCallId()).isEqualTo("tc_a");
    assertThat(store.claim(SCOPE, key)).isInstanceOf(Claim.Replay.class);
    assertThat(store.find(SCOPE, key)).map(ToolInvoke.Response::toolCallId).contains("tc_a");
  }

  @Test
  void releaseWakesWaiterExceptionallyAndAllowsReclaim() {
    String key = "k2";
    assertThat(store.claim(SCOPE, key)).isInstanceOf(Claim.Owner.class);
    Claim second = store.claim(SCOPE, key);
    assertThat(second).isInstanceOf(Claim.Awaiting.class);
    CompletableFuture<ToolInvoke.Response> f = ((Claim.Awaiting) second).future();

    store.release(SCOPE, key);

    assertThat(f.isCompletedExceptionally()).isTrue();
    assertThat(store.claim(SCOPE, key)).isInstanceOf(Claim.Owner.class);
    assertThat(store.find(SCOPE, key)).isEmpty();
  }

  @Test
  void releaseAfterCompleteIsNoop() {
    String key = "k3";
    store.claim(SCOPE, key);
    store.complete(SCOPE, key, response("tc_c"));
    store.release(SCOPE, key);
    assertThat(store.find(SCOPE, key)).isPresent();
    assertThat(store.claim(SCOPE, key)).isInstanceOf(Claim.Replay.class);
  }

  @Test
  void scopeIsolatesKeys() {
    assertThat(store.claim("s1", "same")).isInstanceOf(Claim.Owner.class);
    assertThat(store.claim("s2", "same")).isInstanceOf(Claim.Owner.class);
  }

  @Test
  void findIsEmptyWhileClaimedOrUnknown() {
    assertThat(store.find(SCOPE, "unknown")).isEmpty();
    store.claim(SCOPE, "held");
    assertThat(store.find(SCOPE, "held")).isEmpty();
  }

  // ---------------------------------------------------------------- TTL 淘汰（B2）

  /** 可手动前进的时钟。 */
  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static final Instant T0 = Instant.parse("2026-09-13T00:00:00Z");

  /**
   * 已完成且超过 TTL 的记录被清掉；未过期的保留。
   *
   * <p>release 刻意不删已完成的 key（否则重放失效），于是每次幂等写留一条永久记录——不淘汰就随写操作数无界增长。
   */
  @Test
  void sweepRemovesCompletedEntriesOlderThanTtl() {
    MutableClock clock = new MutableClock(T0);
    InMemoryIdempotencyStore s = new InMemoryIdempotencyStore(Duration.ofHours(1), clock);
    s.claim(SCOPE, "old");
    s.complete(SCOPE, "old", response("tc_old"));
    clock.advance(Duration.ofMinutes(59));
    s.claim(SCOPE, "fresh");
    s.complete(SCOPE, "fresh", response("tc_fresh"));
    clock.advance(Duration.ofMinutes(2));

    s.sweep();

    assertThat(s.find(SCOPE, "old")).as("完成于 61 分钟前，应被淘汰").isEmpty();
    assertThat(s.find(SCOPE, "fresh")).as("完成于 2 分钟前，应保留").isPresent();
    assertThat(s.size()).isEqualTo(1);
  }

  /** 占位中的槽位没有 completedAt，无论多久都不能被清扫——它由执行者的 complete / release 收尾。 */
  @Test
  void sweepNeverRemovesPendingClaims() {
    MutableClock clock = new MutableClock(T0);
    InMemoryIdempotencyStore s = new InMemoryIdempotencyStore(Duration.ofMinutes(1), clock);
    s.claim(SCOPE, "pending");
    clock.advance(Duration.ofDays(1));

    s.sweep();

    assertThat(s.size()).isEqualTo(1);
    assertThat(s.claim(SCOPE, "pending")).isInstanceOf(Claim.Awaiting.class);
  }

  /** 清扫按 complete 次数节流触发，不需要外部调度器。 */
  @Test
  void completeTriggersSweepEveryNCalls() {
    MutableClock clock = new MutableClock(T0);
    InMemoryIdempotencyStore s = new InMemoryIdempotencyStore(Duration.ofMinutes(1), clock);
    s.claim(SCOPE, "stale");
    s.complete(SCOPE, "stale", response("tc_stale"));
    clock.advance(Duration.ofMinutes(5));
    // 再做 SWEEP_EVERY - 1 次 complete：第 SWEEP_EVERY 次触发清扫
    for (int i = 1; i < InMemoryIdempotencyStore.SWEEP_EVERY; i++) {
      s.claim(SCOPE, "k" + i);
      s.complete(SCOPE, "k" + i, response("tc_" + i));
    }

    assertThat(s.find(SCOPE, "stale")).as("第 SWEEP_EVERY 次 complete 应触发清扫").isEmpty();
    assertThat(s.find(SCOPE, "k1")).isPresent();
  }

  private static ToolInvoke.Response response(String toolCallId) {
    return new ToolInvoke.Response(toolCallId, SseEvent.ToolStatus.succeeded, 0, null, null);
  }
}
