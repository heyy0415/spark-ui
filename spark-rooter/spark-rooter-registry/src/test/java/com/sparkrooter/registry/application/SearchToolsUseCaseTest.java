package com.sparkrooter.registry.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.contracts.model.ToolSearch;
import com.sparkrooter.registry.infra.InMemoryToolRegistryRepository;
import com.sparkrooter.registry.support.Manifests;
import com.sparkrooter.registry.support.Providers;
import com.sparkrooter.spi.ToolAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 搜索用例：domain 为空返回全部；只返回可发现状态；宿主 ToolAccessPolicy 按 sessionId 过滤；候选恰六字段。 */
final class SearchToolsUseCaseTest {

  private final InMemoryToolRegistryRepository repo = new InMemoryToolRegistryRepository();

  @BeforeEach
  void seed() {
    repo.putIfAbsent(Manifests.of("a.list.get", "1.0.0", "a"));
    repo.putIfAbsent(Manifests.of("a.detail.get", "1.0.0", "a"));
    repo.putIfAbsent(Manifests.of("b.list.get", "1.0.0", "b"));
    repo.putIfAbsent(
        Manifests.bind(Manifests.json("b.hidden.get", "1.0.0", "b").put("status", "draft")));
  }

  @Test
  void blankDomainReturnsAllDiscoverable() {
    SearchToolsUseCase useCase = new SearchToolsUseCase(repo, Providers.none());
    ToolSearch.Response r = useCase.search(new ToolSearch.Request(null, null, null), "s1");
    assertThat(r.tools())
        .extracting(ToolSearch.ToolCandidate::toolId)
        .containsExactly("a.detail.get", "a.list.get", "b.list.get");
    assertThat(useCase.domains()).containsExactlyInAnyOrder("a", "b");
  }

  @Test
  void domainNarrowsPool() {
    SearchToolsUseCase useCase = new SearchToolsUseCase(repo, Providers.none());
    ToolSearch.Response r = useCase.search(new ToolSearch.Request("b", null, null), "s1");
    assertThat(r.tools())
        .extracting(ToolSearch.ToolCandidate::toolId)
        .containsExactly("b.list.get");
  }

  @Test
  void hostAccessPolicyFiltersBySession() {
    ToolAccessPolicy denyDetailForGuest =
        (toolId, sessionId) -> !("guest".equals(sessionId) && toolId.endsWith(".detail.get"));
    SearchToolsUseCase useCase = new SearchToolsUseCase(repo, Providers.of(denyDetailForGuest));

    assertThat(useCase.search(new ToolSearch.Request(null, null, null), "guest").tools())
        .extracting(ToolSearch.ToolCandidate::toolId)
        .containsExactly("a.list.get", "b.list.get");
    assertThat(useCase.search(new ToolSearch.Request(null, null, null), "admin").tools())
        .hasSize(3);
  }

  @Test
  void candidateExposesExactlySixFields() {
    ToolManifest m = Manifests.example();
    ToolSearch.ToolCandidate c = ToolSearch.ToolCandidate.from(m);
    // agent-safety §2：返回给模型的字段只有这六个，不含 owner / authorization / outputSchema
    var node = Manifests.VALIDATOR.mapper().valueToTree(c);
    assertThat(node.properties()).hasSize(6);
    assertThat(node.has("owner")).isFalse();
    assertThat(node.has("authorization")).isFalse();
    assertThat(node.has("outputSchema")).isFalse();
    assertThat(c.toolId()).isEqualTo(m.toolId());
    assertThat(c.riskLevel()).isEqualTo(m.risk().level());
    assertThat(c.confirmation()).isEqualTo(m.risk().confirmation());
  }
}
