package com.sparkrooter.spi;

import java.util.Optional;

/**
 * {@code serviceName → base URL} 的解析端口。
 *
 * <p>默认实现直接取 Manifest 里的 {@code provider.baseUrl}（配置化地址，适合内网固定部署）。 宿主接 Eureka / Nacos / K8s DNS
 * 时定义同类型 Bean 即覆盖——本项目不做服务发现（非目标）， 但把这个缝留出来，免得接注册中心的宿主要改内核。
 */
public interface ProviderEndpointResolver {

  /**
   * 解析服务地址。
   *
   * @param serviceName Manifest 声明的逻辑服务名
   * @param declaredBaseUrl Manifest 里的 {@code provider.baseUrl}，可能为 null
   * @return base URL；{@link Optional#empty()} 表示无法寻址，Gateway 据此报 UNREACHABLE
   */
  Optional<String> resolve(String serviceName, String declaredBaseUrl);
}
