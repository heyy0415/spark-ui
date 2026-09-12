package com.sparkrooter.gateway.infra.transport;

import com.sparkrooter.spi.ProviderEndpointResolver;
import java.util.Optional;

/**
 * 默认实现：直接取 Manifest 里声明的 {@code provider.baseUrl}。
 *
 * <p>适合内网固定部署（provider 启动时把自己的地址写进 Manifest 推给 hub）。本项目不做服务发现 （非目标），接 Eureka / Nacos / K8s DNS
 * 的宿主定义同类型 Bean 即覆盖。
 *
 * <p>拿不到地址时返回 {@link Optional#empty()} 而非抛异常：由 {@link HttpToolTransport} 统一 转成 {@code
 * UNREACHABLE}（可安全重试），错误分类集中在一处。
 */
public class ManifestProviderEndpointResolver implements ProviderEndpointResolver {

  @Override
  public Optional<String> resolve(String serviceName, String declaredBaseUrl) {
    if (declaredBaseUrl == null || declaredBaseUrl.isBlank()) {
      return Optional.empty();
    }
    // 去掉尾部斜杠：避免与 INVOKE_PATH 拼出双斜杠
    String trimmed =
        declaredBaseUrl.endsWith("/")
            ? declaredBaseUrl.substring(0, declaredBaseUrl.length() - 1)
            : declaredBaseUrl;
    return Optional.of(trimmed);
  }
}
