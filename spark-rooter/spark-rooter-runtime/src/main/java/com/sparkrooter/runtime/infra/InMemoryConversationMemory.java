package com.sparkrooter.runtime.infra;

import com.sparkrooter.spi.ConversationMemory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 内存会话记忆：TTL 到期即视为不存在（惰性淘汰 + 写入时顺手清理过期项）。只存 ID，不存业务数据、不存用户。 */
public class InMemoryConversationMemory implements ConversationMemory {

  private final Map<String, Memory> store = new ConcurrentHashMap<>();
  private final Duration ttl;
  private final Clock clock;

  public InMemoryConversationMemory(Duration ttl, Clock clock) {
    this.ttl = ttl;
    this.clock = clock;
  }

  @Override
  public void put(String conversationId, Memory memory) {
    Instant now = Instant.now(clock);
    store.entrySet().removeIf(e -> expired(e.getValue(), now));
    store.put(conversationId, memory);
  }

  @Override
  public Optional<Memory> find(String conversationId) {
    Memory m = store.get(conversationId);
    if (m == null) {
      return Optional.empty();
    }
    if (expired(m, Instant.now(clock))) {
      store.remove(conversationId, m);
      return Optional.empty();
    }
    return Optional.of(m);
  }

  private boolean expired(Memory m, Instant now) {
    return !now.isBefore(m.at().plus(ttl));
  }
}
