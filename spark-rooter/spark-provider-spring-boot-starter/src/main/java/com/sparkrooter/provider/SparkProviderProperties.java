package com.sparkrooter.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * provider 侧配置（前缀 {@code spark.provider}）。
 *
 * @param hubUrl hub 的基础地址，如 {@code http://spark-hub.internal:8080}。**必填**，缺失则拒绝启动
 * @param serviceName 本服务的逻辑名，写进 Manifest 的 {@code provider.serviceName}，与认证密钥绑定。**必填**
 * @param baseUrl 本服务对 hub 可见的地址，写进 Manifest 的 {@code provider.baseUrl}；缺省则由 hub 侧 {@code
 *     ProviderEndpointResolver} 按 serviceName 解析（接注册中心场景）
 * @param token 与 hub 的共享密钥，双向认证共用。**必填**，缺失则拒绝启动（安全缺省不能是宽松的）
 * @param ownerTeam Manifest 的 {@code owner.team}
 * @param publishOnStartup 启动后是否自动推送 Manifest；设 false 供测试或手工推送场景
 */
@ConfigurationProperties(prefix = "spark.provider")
public record SparkProviderProperties(
    String hubUrl,
    String serviceName,
    String baseUrl,
    String token,
    String ownerTeam,
    Boolean publishOnStartup) {

  /** 连接 hub 的超时；推送 Manifest 是启动期动作，不宜卡太久。 */
  public static final int HUB_CONNECT_TIMEOUT_MS = 3000;

  /** 读 hub 响应的超时。 */
  public static final int HUB_READ_TIMEOUT_MS = 5000;

  public SparkProviderProperties {
    ownerTeam = ownerTeam == null || ownerTeam.isBlank() ? "unknown" : ownerTeam;
    publishOnStartup = publishOnStartup == null || publishOnStartup;
  }

  /** 是否启动后自动推送。 */
  public boolean shouldPublishOnStartup() {
    return Boolean.TRUE.equals(publishOnStartup);
  }
}
