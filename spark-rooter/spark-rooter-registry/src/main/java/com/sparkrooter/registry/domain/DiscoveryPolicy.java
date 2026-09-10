package com.sparkrooter.registry.domain;

import com.sparkrooter.contracts.model.ToolManifest;
import java.util.List;

/**
 * 发现过滤规则（agent-safety §2）：只返回 status ∈ {active, canary} 的工具。权限不在内核，由宿主 ToolAccessPolicy 决定。
 * 纯函数，无框架依赖。
 */
public final class DiscoveryPolicy {

  private DiscoveryPolicy() {}

  public static boolean discoverable(ToolManifest m) {
    return m.status() == ToolManifest.Status.active || m.status() == ToolManifest.Status.canary;
  }

  public static List<ToolManifest> filter(List<ToolManifest> candidates) {
    return candidates.stream().filter(DiscoveryPolicy::discoverable).toList();
  }
}
