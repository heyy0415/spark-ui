package com.sparkrooter.registry.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.registry.support.Manifests;
import org.junit.jupiter.api.Test;

/** 内存注册表：toolId@version 唯一、不可覆盖；按领域 / 版本 / 全量查询结果按键排序稳定。 */
final class InMemoryToolRegistryRepositoryTest {

  private final InMemoryToolRegistryRepository repo = new InMemoryToolRegistryRepository();

  @Test
  void putIfAbsentRejectsSameKeyButAllowsNewVersion() {
    ToolManifest v1 = Manifests.of("a.b.c", "1.0.0", "a");
    assertThat(repo.putIfAbsent(v1)).isTrue();
    assertThat(repo.putIfAbsent(Manifests.of("a.b.c", "1.0.0", "a"))).isFalse();
    assertThat(repo.putIfAbsent(Manifests.of("a.b.c", "1.1.0", "a"))).isTrue();
    // 已发布版本不可变：第二次 put 不会覆盖第一次的内容
    assertThat(repo.find("a.b.c", "1.0.0")).contains(v1);
  }

  @Test
  void findReturnsEmptyForUnknownKey() {
    assertThat(repo.find("x.y.z", "9.9.9")).isEmpty();
  }

  @Test
  void queriesAreSortedByKey() {
    repo.putIfAbsent(Manifests.of("b.list.get", "1.0.0", "b"));
    repo.putIfAbsent(Manifests.of("a.list.get", "2.0.0", "a"));
    repo.putIfAbsent(Manifests.of("a.list.get", "1.0.0", "a"));
    repo.putIfAbsent(Manifests.of("a.detail.get", "1.0.0", "a"));

    assertThat(repo.findAll())
        .extracting(ToolManifest::key)
        .containsExactly(
            "a.detail.get@1.0.0", "a.list.get@1.0.0", "a.list.get@2.0.0", "b.list.get@1.0.0");
    assertThat(repo.findByDomain("a"))
        .extracting(ToolManifest::key)
        .containsExactly("a.detail.get@1.0.0", "a.list.get@1.0.0", "a.list.get@2.0.0");
    assertThat(repo.findVersions("a.list.get"))
        .extracting(ToolManifest::version)
        .containsExactly("1.0.0", "2.0.0");
    assertThat(repo.findByDomain("nope")).isEmpty();
  }
}
