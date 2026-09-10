package com.sparkrooter.registry.application;

import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.contracts.model.ToolSearch;
import com.sparkrooter.registry.api.ToolSearchPort;
import com.sparkrooter.registry.domain.DiscoveryPolicy;
import com.sparkrooter.registry.domain.ToolRegistryRepository;
import com.sparkrooter.spi.Principal;
import com.sparkrooter.spi.PrincipalPermissionResolver;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 发现用例：按 domain 取候选 → 按 principal 权限与 status 过滤 → 投影为六字段候选。 */
@Service
public class SearchToolsUseCase implements ToolSearchPort {

  private static final Logger log = LoggerFactory.getLogger(SearchToolsUseCase.class);

  private final ToolRegistryRepository repo;
  private final PrincipalPermissionResolver permissions;

  public SearchToolsUseCase(ToolRegistryRepository repo, PrincipalPermissionResolver permissions) {
    this.repo = repo;
    this.permissions = permissions;
  }

  @Override
  public ToolSearch.Response search(ToolSearch.Request req) {
    return execute(req);
  }

  @Override
  public Set<String> domains(ToolSearch.Principal principal) {
    Principal p = new Principal(principal.userId(), principal.tenantId());
    Set<String> perms = permissions.permissionsOf(p);
    return DiscoveryPolicy.filter(repo.findAll(), perms).stream()
        .map(ToolManifest::domain)
        .collect(Collectors.toUnmodifiableSet());
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
