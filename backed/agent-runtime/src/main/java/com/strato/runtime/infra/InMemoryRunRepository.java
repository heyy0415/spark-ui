package com.strato.runtime.infra;

import com.strato.runtime.domain.Run;
import com.strato.runtime.domain.RunRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/** 内存 Run 仓储。 */
@Repository
public class InMemoryRunRepository implements RunRepository {
  private final Map<String, Run> store = new ConcurrentHashMap<>();

  @Override
  public void save(Run run) {
    store.put(run.runId(), run);
  }

  @Override
  public Optional<Run> find(String runId) {
    return Optional.ofNullable(store.get(runId));
  }
}
