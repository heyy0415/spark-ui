package com.sparkrooter.runtime.domain;

import java.util.Optional;

/** Run 仓储端口；首期内存。 */
public interface RunRepository {
  void save(Run run);

  Optional<Run> find(String runId);
}
