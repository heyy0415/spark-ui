package com.sparkrooter.examples.support;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 示例宿主的用户上下文（ThreadLocal）。真实宿主用自己的登录态 / SecurityContext 替代；spark-rooter 内核不依赖它。
 *
 * <p>spark-rooter 在自有线程池调用工具方法，本上下文不会自动跨线程：示例宿主用 DemoContextPropagator（实现 spi
 * RunContextPropagator）在请求线程 capture、工作线程 restore。未设置时回落到默认演示用户，保证种子数据可直接跑通。
 */
public final class DemoUserContext {

  /** 演示用户：userId、tenantId、角色集合。 */
  public record DemoUser(String userId, String tenantId, Set<String> roles) {
    public DemoUser {
      Objects.requireNonNull(userId, "userId");
      Objects.requireNonNull(tenantId, "tenantId");
      roles = Set.copyOf(roles);
    }

    public boolean hasRole(String role) {
      return roles.contains(role);
    }
  }

  /** 默认演示用户：种子数据全部属于 tenant_001；user_001 是管理员。 */
  public static final DemoUser DEFAULT = new DemoUser("user_001", "tenant_001", Set.of("admin"));

  private static final ThreadLocal<DemoUser> CURRENT = new ThreadLocal<>();

  private DemoUserContext() {}

  public static void set(DemoUser user) {
    CURRENT.set(user);
  }

  public static void clear() {
    CURRENT.remove();
  }

  /** 当前线程显式设置的用户；未设置为 empty（供传播器 capture）。 */
  public static Optional<DemoUser> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  /** 当前用户，未设置回落默认。 */
  public static DemoUser currentOrDefault() {
    return current().orElse(DEFAULT);
  }

  public static String tenantId() {
    return currentOrDefault().tenantId();
  }

  public static String userId() {
    return currentOrDefault().userId();
  }
}
