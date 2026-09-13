package com.sparkrooter.gateway.infra;

import com.sparkrooter.contracts.model.ToolInvoke;
import com.sparkrooter.gateway.domain.IdempotencyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存幂等表：值是 CompletableFuture，未完成 = 占位中，已完成 = 最终结果。
 *
 * <p><b>已完成的结果按 TTL 淘汰</b>：{@code release} 刻意不删已完成的 key（否则重放失效），于是每次幂等写都会留下一条永久记录，
 * 长跑进程随写操作数无界增长。淘汰在 {@link #complete} 里按计数节流（每 {@value #SWEEP_EVERY} 次扫一遍），不引调度器；
 * 未完成的占位永不被淘汰——它们由执行者的 complete / release 负责收尾。
 *
 * <p>单副本形态用；多副本请换 {@code spark-rooter-redis} 的实现（TTL 由 Redis 负责）。
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

  /** 每多少次 complete 触发一次清扫。全表扫描是 O(n)，每次写都扫会让写操作的成本随历史线性增长。 */
  static final int SWEEP_EVERY = 64;

  /** 默认保留窗口：应长于任何合理的客户端重试窗口，短于一天没人会重试同一个 key。 */
  static final Duration DEFAULT_TTL = Duration.ofHours(24);

  /** 一个 key 的槽位：future 承载结果，completedAt 只在 complete 时写一次（volatile 供清扫线程读）。 */
  private static final class Slot {
    final CompletableFuture<ToolInvoke.Response> future = new CompletableFuture<>();
    volatile Instant completedAt;
  }

  private final Map<String, Slot> store = new ConcurrentHashMap<>();
  private final Duration ttl;
  private final Clock clock;
  private final AtomicLong completes = new AtomicLong();

  public InMemoryIdempotencyStore() {
    this(DEFAULT_TTL, Clock.systemUTC());
  }

  public InMemoryIdempotencyStore(Duration ttl, Clock clock) {
    this.ttl = ttl;
    this.clock = clock;
  }

  private static String key(String scope, String idem) {
    return scope + "/" + idem;
  }

  @Override
  public Claim claim(String scope, String idempotencyKey) {
    Slot mine = new Slot();
    Slot existing = store.putIfAbsent(key(scope, idempotencyKey), mine);
    if (existing == null) {
      return new Claim.Owner();
    }
    if (existing.future.isDone() && !existing.future.isCompletedExceptionally()) {
      return new Claim.Replay(existing.future.join());
    }
    return new Claim.Awaiting(existing.future);
  }

  @Override
  public void complete(String scope, String idempotencyKey, ToolInvoke.Response response) {
    Slot s = store.get(key(scope, idempotencyKey));
    if (s != null) {
      s.completedAt = Instant.now(clock);
      s.future.complete(response);
    }
    if (completes.incrementAndGet() % SWEEP_EVERY == 0) {
      sweep();
    }
  }

  @Override
  public void release(String scope, String idempotencyKey) {
    String k = key(scope, idempotencyKey);
    Slot s = store.get(k);
    // 只释放未完成的占位；已 complete 的结果必须保留，否则重放失效
    if (s != null && !s.future.isDone()) {
      store.remove(k, s);
      s.future.completeExceptionally(new IllegalStateException("idempotency claim released"));
    }
  }

  @Override
  public Optional<ToolInvoke.Response> find(String scope, String idempotencyKey) {
    Slot s = store.get(key(scope, idempotencyKey));
    if (s == null || !s.future.isDone() || s.future.isCompletedExceptionally()) {
      return Optional.empty();
    }
    return Optional.of(s.future.join());
  }

  /** 删除已完成且超过 TTL 的记录；占位中的槽位（completedAt 为 null）不动。供测试直接调用。 */
  void sweep() {
    Instant cutoff = Instant.now(clock).minus(ttl);
    store
        .entrySet()
        .removeIf(
            e -> {
              Instant at = e.getValue().completedAt;
              return at != null && at.isBefore(cutoff);
            });
  }

  /** 当前表大小，供测试与自检。 */
  public int size() {
    return store.size();
  }
}
