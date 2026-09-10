package com.sparkrooter.app.infra;

import com.sparkrooter.spi.Principal;
import com.sparkrooter.spi.PrincipalPermissionResolver;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 首期内存权限表。真实 IdP 为后续 change。
 *
 * <p>配置用显式列表而非 Map：Spring Boot 的宽松绑定会把 Map 键中的 "@" 与值中的 ":" 当作特殊字符导致绑定为空（T06 验收时暴露）。
 */
@Component
@EnableConfigurationProperties(InMemoryPrincipalPermissionResolver.Table.class)
public class InMemoryPrincipalPermissionResolver implements PrincipalPermissionResolver {

  private static final Logger log =
      LoggerFactory.getLogger(InMemoryPrincipalPermissionResolver.class);

  /** spark.permissions.grants: - { userId, tenantId, permissions: [...] } */
  @ConfigurationProperties(prefix = "spark.permissions")
  public record Table(List<Grant> grants) {
    public Table {
      grants = grants == null ? List.of() : List.copyOf(grants);
    }
  }

  public record Grant(String userId, String tenantId, List<String> permissions) {}

  private final Map<String, Set<String>> byPrincipal;

  public InMemoryPrincipalPermissionResolver(Table table) {
    Map<String, Set<String>> m = new HashMap<>();
    for (Grant g : table.grants()) {
      m.put(key(g.userId(), g.tenantId()), Set.copyOf(new HashSet<>(g.permissions())));
    }
    this.byPrincipal = Collections.unmodifiableMap(m);
    log.info("permission table loaded: {} principals", byPrincipal.size());
  }

  private static String key(String userId, String tenantId) {
    return userId + "@" + tenantId;
  }

  @Override
  public Set<String> permissionsOf(Principal p) {
    return byPrincipal.getOrDefault(key(p.userId(), p.tenantId()), Set.of());
  }
}
