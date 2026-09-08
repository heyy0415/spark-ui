package com.strato.registry.domain;

import com.strato.contracts.model.ToolManifest;
import java.util.List;
import java.util.Optional;

/** Registry 存储端口。首期内存实现；不含任何转发 / 调用能力（agent-safety §1）。 */
public interface ToolRegistryRepository {

  /** 原子地"不存在则插入"；已存在返回 false。 */
  boolean putIfAbsent(ToolManifest manifest);

  Optional<ToolManifest> find(String toolId, String version);

  List<ToolManifest> findByDomain(String domain);

  List<ToolManifest> findVersions(String toolId);

  /** 全部已注册 Manifest（供按 principal 过滤后的领域枚举）。 */
  List<ToolManifest> findAll();
}
