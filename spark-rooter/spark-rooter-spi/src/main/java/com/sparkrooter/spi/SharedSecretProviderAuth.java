package com.sparkrooter.spi;

import java.util.Map;

/**
 * 共享密钥实现：一个 {@code serviceName → token} 映射。
 *
 * <p>hub 侧持全部 provider 的密钥；provider 侧只持自己那一条。两侧共用本类，出站时按被调服务名取key。
 *
 * <p>构造期即拒绝空映射 / 空密钥 —— 安全相关的配置缺失必须启动失败，不能悄悄放行。
 */
public final class SharedSecretProviderAuth implements ProviderAuth {

  private final Map<String, String> tokensByService;

  /**
   * @param tokensByService 已知服务的密钥；不得为空，值不得空白。hub 侧持全部 provider 的密钥； provider 侧只持自己那一条（出站调 hub
   *     时用同一条）
   */
  public SharedSecretProviderAuth(Map<String, String> tokensByService) {
    if (tokensByService == null || tokensByService.isEmpty()) {
      throw new IllegalStateException(
          "spark provider auth: no service tokens configured; refusing to start"
              + " (a permissive default would let any caller register forged tools)");
    }
    tokensByService.forEach(
        (service, token) -> {
          if (service == null || service.isBlank()) {
            throw new IllegalStateException("spark provider auth: blank serviceName in token map");
          }
          if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                "spark provider auth: blank token for service " + service);
          }
        });
    this.tokensByService = Map.copyOf(tokensByService);
  }

  /**
   * 令牌必须与声称的 serviceName 对应。
   *
   * <p>只比「令牌在不在已知集合里」是不够的：那样服务 A 的密钥就能注册服务 B 的工具。
   */
  @Override
  public boolean verify(String serviceName, String token) {
    if (serviceName == null || token == null) {
      return false;
    }
    String expected = tokensByService.get(serviceName);
    // 未知服务也走一次比较：避免用"是否立即返回"泄漏服务名是否存在
    return ProviderAuth.constantTimeEquals(expected == null ? "" : expected, token)
        && expected != null;
  }

  @Override
  public String outboundToken(String serviceName) {
    return serviceName == null ? null : tokensByService.get(serviceName);
  }
}
