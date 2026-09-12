package com.sparkrooter.registry.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparkrooter.spi.ProviderAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manifest 注册的准入判定（feat-provider-http-transport §1.2）。
 *
 * <p>背景：注册端点原本无任何认证。单体内网下尚可（端点不对外暴露），但一旦 provider 跨服务推送， <b>任何能访问该端点的调用方都能注册任意工具</b>——包括伪造一个 {@code
 * sideEffect=false} 的假查询工具 实际执行转账。契约层的 {@code high ⇒ required} 拦不住这种「低风险伪装」。
 *
 * <p>两种形态的准入规则不同：
 *
 * <ul>
 *   <li><b>单体</b>（无 {@link ProviderAuth} Bean）：只接受 {@code protocol=in-process} 的 Manifest，
 *       且它们来自本进程的 {@code @SparkTool} 扫描，无外部输入面 —— 无需令牌
 *   <li><b>微服务</b>（有 {@link ProviderAuth} Bean）：{@code protocol=http} 必须出示令牌，且令牌 必须与 Manifest 声称的
 *       {@code provider.serviceName} 绑定
 * </ul>
 *
 * <p>关键设计：**未装配认证时不是"放行一切"，而是"拒绝一切远程注册"**。若缺 Bean 就放行 http 注册， 等于给未配置认证的部署留一个静默后门。
 */
public final class RegistrationGuard {

  private static final Logger log = LoggerFactory.getLogger(RegistrationGuard.class);

  private final ProviderAuth auth;

  /**
   * @param auth 宿主装配的认证实现；null = 未装配（单体形态）
   */
  public RegistrationGuard(ProviderAuth auth) {
    this.auth = auth;
  }

  /** 准入失败。调用方（Controller）映射为 401/403，不回显令牌内容。 */
  public static final class Denied extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public Denied(String message) {
      super(message);
    }
  }

  /**
   * 校验一次注册请求。
   *
   * @param manifest 待注册的 Manifest（未经契约校验，故用 path() 安全取值）
   * @param token 入站令牌，可能为 null
   * @throws Denied 不满足准入条件
   */
  public void check(JsonNode manifest, String token) {
    String protocol = manifest.path("protocol").asText("");
    String toolId = manifest.path("toolId").asText("?");

    if (!"http".equals(protocol)) {
      // in-process / 未知协议：由后续契约校验与 Registry 判定；本守卫只管远程注册的准入
      return;
    }

    if (auth == null) {
      log.warn("register_denied toolId={} reason=no_provider_auth_configured", toolId);
      throw new Denied(
          "remote tool registration requires provider auth; none is configured on this hub");
    }

    String serviceName = manifest.path("provider").path("serviceName").asText("");
    if (serviceName.isEmpty()) {
      // 契约的 if/then 也会拦，但这里先拦：没有 serviceName 就无法绑定令牌
      log.warn("register_denied toolId={} reason=missing_service_name", toolId);
      throw new Denied("provider.serviceName is required for remote registration");
    }

    if (!auth.verify(serviceName, token)) {
      // 不记录令牌内容；serviceName 是调用方声称的，可安全入日志
      log.warn("register_denied toolId={} serviceName={} reason=bad_token", toolId, serviceName);
      throw new Denied("invalid provider token for service " + serviceName);
    }

    log.info("register_allowed toolId={} serviceName={}", toolId, serviceName);
  }
}
