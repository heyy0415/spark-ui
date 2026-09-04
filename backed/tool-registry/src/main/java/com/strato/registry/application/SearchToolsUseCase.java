package com.strato.registry.application;

import com.strato.contracts.model.ToolManifest;
import com.strato.contracts.model.ToolSearch;
import com.strato.registry.domain.DiscoveryPolicy;
import com.strato.registry.domain.ToolRegistryRepository;
import com.strato.spi.Principal;
import com.strato.spi.PrincipalPermissionResolver;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 发现用例：按 domain 取候选 → 按 principal 权限与 status 过滤 → 投影为六字段候选。 */
@Service
public class SearchToolsUseCase {

  private static final Logger log = LoggerFactory.getLogger(SearchToolsUseCase.class);

  private final ToolRegistryRepository repo;
  private final PrincipalPermissionResolver permissions;

  public SearchToolsUseCase(ToolRegistryRepository repo, PrincipalPermissionResolver permissions) {
    this.repo = repo;
    this.permissions = permissions;
  }

  public ToolSearch.Response execute(ToolSearch.Request req) {
    Principal p = new Principal(req.principal().userId(), req.principal().tenantId());
    Set<String> perms = permissions.permissionsOf(p);
    List<ToolManifest> visible = DiscoveryPolicy.filter(repo.findByDomain(req.domain()), perms);
    log.info(
        "search domain={} user={} tenant={} candidates={}",
        req.domain(),
        p.userId(),
        p.tenantId(),
        visible.size());
    return new ToolSearch.Response(visible.stream().map(ToolSearch.ToolCandidate::from).toList());
  }
}
