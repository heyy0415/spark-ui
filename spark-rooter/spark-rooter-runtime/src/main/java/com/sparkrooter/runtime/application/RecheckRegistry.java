package com.sparkrooter.runtime.application;

import com.sparkrooter.spi.ConfirmationRecheck;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 需确认工具 → 领域提供的重校验契约（spi ConfirmationRecheck Bean）。同一 toolId 出现两次视为装配错误，启动即失败。 */
public class RecheckRegistry {

  private final Map<String, ConfirmationRecheck> byTool = new HashMap<>();

  public RecheckRegistry(List<ConfirmationRecheck> rechecks) {
    for (ConfirmationRecheck r : rechecks) {
      if (byTool.putIfAbsent(r.toolId(), r) != null) {
        throw new IllegalStateException("duplicate ConfirmationRecheck for " + r.toolId());
      }
    }
  }

  public Optional<ConfirmationRecheck> find(String toolId) {
    return Optional.ofNullable(byTool.get(toolId));
  }

  public Set<String> toolIds() {
    return Set.copyOf(byTool.keySet());
  }
}
