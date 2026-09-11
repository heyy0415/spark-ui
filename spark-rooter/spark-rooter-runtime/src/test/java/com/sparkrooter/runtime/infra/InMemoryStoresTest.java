package com.sparkrooter.runtime.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparkrooter.runtime.domain.ConfirmationToken;
import com.sparkrooter.runtime.domain.Run;
import com.sparkrooter.runtime.domain.RunState;
import com.sparkrooter.spi.ConversationMemory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 三个内存存储：TTL 惰性淘汰、按 updatedAt 回收、令牌一次性消费。都用可调 Clock，不 sleep。 */
final class InMemoryStoresTest {

  private static final Instant T0 = Instant.parse("2026-09-11T00:00:00Z");

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

  // ---------------------------------------------------------------- ConversationMemory

  @Test
  void memoryIsKeyedBySessionAndConversation() {
    MutableClock clock = new MutableClock(T0);
    InMemoryConversationMemory mem = new InMemoryConversationMemory(Duration.ofMinutes(30), clock);
    mem.put("s1", "c1", memory("a", T0));
    assertThat(mem.find("s1", "c1")).map(ConversationMemory.Memory::domain).contains("a");
    // 同 conversationId 不同 sessionId 拿不到：会话号可伪造，隔离键来自宿主
    assertThat(mem.find("s2", "c1")).isEmpty();
  }

  @Test
  void memoryExpiresLazilyByTtl() {
    MutableClock clock = new MutableClock(T0);
    InMemoryConversationMemory mem = new InMemoryConversationMemory(Duration.ofMinutes(30), clock);
    mem.put("s", "c", memory("a", T0));
    clock.advance(Duration.ofMinutes(29));
    assertThat(mem.find("s", "c")).isPresent();
    clock.advance(Duration.ofMinutes(1));
    assertThat(mem.find("s", "c")).isEmpty();
  }

  @Test
  void memoryPutSweepsExpiredEntries() {
    MutableClock clock = new MutableClock(T0);
    InMemoryConversationMemory mem = new InMemoryConversationMemory(Duration.ofMinutes(1), clock);
    mem.put("s", "old", memory("old", T0));
    clock.advance(Duration.ofMinutes(2));
    // 新记忆的 at 取当前时钟，否则自己也已过期
    mem.put("s", "new", memory("new", clock.instant()));
    assertThat(mem.find("s", "old")).isEmpty();
    assertThat(mem.find("s", "new")).isPresent();
  }

  private static ConversationMemory.Memory memory(String domain, Instant at) {
    return new ConversationMemory.Memory(
        domain, Map.of(), new ConversationMemory.LastTable("t", List.of("1"), null), at);
  }

  // ---------------------------------------------------------------- RunRepository

  @Test
  void runRepositoryEvictsByUpdatedAtTtl() {
    MutableClock clock = new MutableClock(T0);
    InMemoryRunRepository repo = new InMemoryRunRepository(Duration.ofHours(1), clock);
    Run stale = new Run("run_stale", "c", "s", "m", T0);
    Run fresh = new Run("run_fresh", "c", "s", "m", T0);
    repo.save(stale);
    repo.save(fresh);
    clock.advance(Duration.ofMinutes(59));
    fresh.transition(RunState.PLANNING, clock.instant()); // updatedAt 刷新
    clock.advance(Duration.ofMinutes(1));

    List<String> gone = repo.evictExpired();

    assertThat(gone).containsExactly("run_stale");
    assertThat(repo.find("run_stale")).isEmpty();
    assertThat(repo.find("run_fresh")).isPresent();
  }

  @Test
  void runRepositorySaveOverwritesSameRunId() {
    InMemoryRunRepository repo =
        new InMemoryRunRepository(Duration.ofHours(1), Clock.fixed(T0, ZoneOffset.UTC));
    Run r = new Run("run_1", "c", "s", "m", T0);
    repo.save(r);
    r.transition(RunState.PLANNING, T0);
    repo.save(r);
    assertThat(repo.find("run_1")).map(Run::state).contains(RunState.PLANNING);
    assertThat(repo.find("nope")).isEmpty();
  }

  // ---------------------------------------------------------------- ConfirmationTokenStore

  @Test
  void tokenStoreConsumeIsOneShot() {
    InMemoryConfirmationTokenStore store = new InMemoryConfirmationTokenStore();
    ConfirmationToken t =
        new ConfirmationToken("ct_abc", "run_1", "confirm", 1, "d", "c", "s", Set.of(), T0);
    store.put(t);
    assertThat(store.consume("ct_abc")).contains(t);
    assertThat(store.consume("ct_abc")).isEmpty();
    assertThat(store.consume("ct_never")).isEmpty();
  }

  @Test
  void tokenRecordCopiesFormKeys() {
    Set<String> keys = new java.util.HashSet<>(Set.of("reason"));
    ConfirmationToken t =
        new ConfirmationToken("ct_x", "run_1", "confirm", 1, "d", "c", "s", keys, T0);
    keys.add("amount");
    assertThat(t.allowedFormKeys()).containsExactly("reason");
  }
}
