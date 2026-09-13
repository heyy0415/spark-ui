package com.sparkrooter.runtime.infra;

import com.sparkrooter.runtime.domain.ConfirmationToken;
import com.sparkrooter.runtime.domain.ConfirmationTokenStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存令牌表；consume = remove，天然一次性。
 *
 * <p><b>写入时顺手清扫过期项</b>（与 {@link InMemoryConversationMemory} 同一手法）：令牌只在被消费时离开表，
 * 而用户看到确认屏却不点（关页面、走开）是常态——这些令牌到期后没人再来消费它们。不清扫的话表随「发起高风险操作但未确认」 的次数无界增长。放在 {@code put}
 * 而不是定时器：没有新确认屏就没有新增长，不需要额外线程。
 *
 * <p>单副本形态用；多副本请换 {@code spark-rooter-redis} 的实现（TTL 由 Redis 负责）。
 */
public class InMemoryConfirmationTokenStore implements ConfirmationTokenStore {
  private final Map<String, ConfirmationToken> store = new ConcurrentHashMap<>();
  private final Clock clock;

  public InMemoryConfirmationTokenStore() {
    this(Clock.systemUTC());
  }

  public InMemoryConfirmationTokenStore(Clock clock) {
    this.clock = clock;
  }

  @Override
  public void put(ConfirmationToken token) {
    Instant now = Instant.now(clock);
    store.values().removeIf(t -> t.expired(now));
    store.put(token.token(), token);
  }

  @Override
  public Optional<ConfirmationToken> consume(String token) {
    return Optional.ofNullable(store.remove(token));
  }

  /** 当前表大小，供测试与自检（正常应随确认完成 / 到期回落）。 */
  public int size() {
    return store.size();
  }
}
