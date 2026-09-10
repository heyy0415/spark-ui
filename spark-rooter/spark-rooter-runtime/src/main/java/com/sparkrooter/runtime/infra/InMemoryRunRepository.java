package com.sparkrooter.runtime.infra;

import com.sparkrooter.runtime.domain.Run;
import com.sparkrooter.runtime.domain.RunRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 内存 Run 仓储：按 updatedAt + TTL 淘汰（终态或长期无更新的 Run 都会被回收），保证长跑进程不无界增长。 */
public class InMemoryRunRepository implements RunRepository {

  private final Map<String, Run> store = new ConcurrentHashMap<>();
  private final Duration ttl;
  private final Clock clock;

  public InMemoryRunRepository(Duration ttl, Clock clock) {
    this.ttl = ttl;
    this.clock = clock;
  }

  @Override
  public void save(Run run) {
    store.put(run.runId(), run);
  }

  @Override
  public Optional<Run> find(String runId) {
    return Optional.ofNullable(store.get(runId));
  }

  @Override
  public List<String> evictExpired() {
    Instant now = Instant.now(clock);
    List<String> gone = new ArrayList<>();
    store
        .entrySet()
        .removeIf(
            e -> {
              boolean expired = !now.isBefore(e.getValue().updatedAt().plus(ttl));
              if (expired) {
                gone.add(e.getKey());
              }
              return expired;
            });
    return gone;
  }
}
