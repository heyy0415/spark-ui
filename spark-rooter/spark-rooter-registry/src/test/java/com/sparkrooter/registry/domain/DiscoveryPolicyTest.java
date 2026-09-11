package com.sparkrooter.registry.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.registry.support.Manifests;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 发现策略：只有 active / canary 对模型可见；draft / deprecated 不进候选。 */
final class DiscoveryPolicyTest {

  @Test
  void onlyActiveAndCanaryAreDiscoverable() {
    assertThat(DiscoveryPolicy.discoverable(Manifests.withStatus(ToolManifest.Status.active)))
        .isTrue();
    assertThat(DiscoveryPolicy.discoverable(Manifests.withStatus(ToolManifest.Status.canary)))
        .isTrue();
    assertThat(DiscoveryPolicy.discoverable(Manifests.withStatus(ToolManifest.Status.draft)))
        .isFalse();
    assertThat(DiscoveryPolicy.discoverable(Manifests.withStatus(ToolManifest.Status.deprecated)))
        .isFalse();
  }

  @Test
  void filterKeepsOrderAndDropsHidden() {
    ToolManifest a = Manifests.withStatus(ToolManifest.Status.active);
    ToolManifest d = Manifests.withStatus(ToolManifest.Status.draft);
    ToolManifest c = Manifests.withStatus(ToolManifest.Status.canary);
    assertThat(DiscoveryPolicy.filter(List.of(a, d, c))).containsExactly(a, c);
  }
}
