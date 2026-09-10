package com.sparkrooter.registry.infra;

import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.registry.domain.ToolRegistryRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 内存实现；键 toolId@version。putIfAbsent 由 ConcurrentHashMap 保证原子。 */
public class InMemoryToolRegistryRepository implements ToolRegistryRepository {

  private final Map<String, ToolManifest> store = new ConcurrentHashMap<>();

  @Override
  public boolean putIfAbsent(ToolManifest manifest) {
    return store.putIfAbsent(manifest.key(), manifest) == null;
  }

  @Override
  public Optional<ToolManifest> find(String toolId, String version) {
    return Optional.ofNullable(store.get(toolId + "@" + version));
  }

  @Override
  public List<ToolManifest> findByDomain(String domain) {
    return store.values().stream()
        .filter(m -> m.domain().equals(domain))
        .sorted((a, b) -> a.key().compareTo(b.key()))
        .toList();
  }

  @Override
  public List<ToolManifest> findAll() {
    return store.values().stream().sorted((a, b) -> a.key().compareTo(b.key())).toList();
  }

  @Override
  public List<ToolManifest> findVersions(String toolId) {
    return store.values().stream()
        .filter(m -> m.toolId().equals(toolId))
        .sorted((a, b) -> a.version().compareTo(b.version()))
        .toList();
  }
}
