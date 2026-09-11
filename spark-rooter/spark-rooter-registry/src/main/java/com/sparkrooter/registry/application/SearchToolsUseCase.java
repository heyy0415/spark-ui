package com.sparkrooter.registry.application;

import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.contracts.model.ToolSearch;
import com.sparkrooter.registry.api.ToolSearchPort;
import com.sparkrooter.registry.domain.DiscoveryPolicy;
import com.sparkrooter.registry.domain.ToolRegistryRepository;
import com.sparkrooter.spi.ToolAccessPolicy;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 发现用例：按 domain 取候选 → 按 status 过滤 → （宿主有 ToolAccessPolicy 时）按策略过滤 → 投影为六字段候选。 内核不识别用户；策略入参只有 toolId
 * 与 sessionId，宿主要按用户判定就从自己传播过来的上下文取。
 */
public class SearchToolsUseCase implements ToolSearchPort {

  private static final Logger log = LoggerFactory.getLogger(SearchToolsUseCase.class);

  private final ToolRegistryRepository repo;
  private final ToolAccessPolicy access;

  public SearchToolsUseCase(ToolRegistryRepository repo, ObjectProvider<ToolAccessPolicy> access) {
    this.repo = repo;
    // 宿主未定义策略 Bean → 全放行
    this.access = access.getIfAvailable(() -> (toolId, sessionId) -> true);
  }

  @Override
  public ToolSearch.Response search(ToolSearch.Request req, String sessionId) {
    return execute(req, sessionId);
  }

  @Override
  public Set<String> domains() {
    return DiscoveryPolicy.filter(repo.findAll()).stream()
        .map(ToolManifest::domain)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * @param sessionId 宿主会话键（可空：进程内自检 / 无会话的内部查询），透传给 ToolAccessPolicy
   */
  public ToolSearch.Response execute(ToolSearch.Request req, String sessionId) {
    // domain 为空 → 全部工具：模型在全部候选里选，内核不做领域路由
    List<ToolManifest> pool =
        req.domain() == null || req.domain().isBlank()
            ? repo.findAll()
            : repo.findByDomain(req.domain());
    List<ToolManifest> visible =
        DiscoveryPolicy.filter(pool).stream()
            .filter(m -> access.allowed(m.toolId(), sessionId))
            .toList();
    log.info(
        "search domain={} candidates={}",
        req.domain() == null ? "*" : req.domain(),
        visible.size());
    return new ToolSearch.Response(visible.stream().map(ToolSearch.ToolCandidate::from).toList());
  }
}
