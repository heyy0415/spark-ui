package com.sparkrooter.runtime.domain;

import java.util.Optional;

/** Run 仓储端口；默认内存实现带 TTL 淘汰（spark.runtime.run-ttl）。 */
public interface RunRepository {
  void save(Run run);

  Optional<Run> find(String runId);

  /** 淘汰过期 Run，返回被淘汰的 runId（编排器据此清理 lastUi 等按 runId 的缓存）。默认无淘汰。 */
  default java.util.List<String> evictExpired() {
    return java.util.List.of();
  }
}
