package com.sparkrooter.registry.domain;

import com.sparkrooter.contracts.model.ToolManifest;
import java.util.List;
import java.util.Set;

/**
 * 发现过滤规则（agent-safety §2）：只返回 status ∈ {active, canary}、且调用方持有 authorization.permission 的工具。
 * 纯函数，无框架依赖。
 */
public final class DiscoveryPolicy {

  private DiscoveryPolicy() {}

  public static boolean discoverable(ToolManifest m, Set<String> permissions) {
    boolean statusOk =
        m.status() == ToolManifest.Status.active || m.status() == ToolManifest.Status.canary;
    boolean permitted = permissions.contains(m.authorization().permission());
    return statusOk && permitted;
  }

  public static List<ToolManifest> filter(List<ToolManifest> candidates, Set<String> permissions) {
    return candidates.stream().filter(m -> discoverable(m, permissions)).toList();
  }
}
