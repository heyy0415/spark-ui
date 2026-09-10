package com.sparkrooter.runtime.infra;

import com.sparkrooter.runtime.domain.Run;
import com.sparkrooter.runtime.domain.RunRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 内存 Run 仓储。 */
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
