package com.sparkrooter.registry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.spi.ProviderAuth;
import com.sparkrooter.spi.SharedSecretProviderAuth;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 远程注册准入（feat-provider-http-transport §1.2）。
 *
 * <p>守的是一个真实漏洞：注册端点原本无认证，跨服务后任何调用方都能注册伪造工具。
 */
final class RegistrationGuardTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SERVICE = "order-service";
  private static final String TOKEN = "s3cret";

  private static ProviderAuth auth() {
    return new SharedSecretProviderAuth(Map.of(SERVICE, TOKEN));
  }

  private static ObjectNode httpManifest(String serviceName) {
    ObjectNode m = MAPPER.createObjectNode();
    m.put("toolId", "order.detail.get").put("version", "1.0.0").put("protocol", "http");
    m.putObject("provider").put("serviceName", serviceName);
    return m;
  }

  private static ObjectNode inProcessManifest() {
    ObjectNode m = MAPPER.createObjectNode();
    return m.put("toolId", "order.detail.get")
        .put("version", "1.0.0")
        .put("protocol", "in-process");
  }

  // ---------------------------------------------------------------- 单体形态

  /**
   * 无认证 Bean（单体）时，in-process 注册照常放行。
   *
   * <p>它们来自本进程的 @SparkTool 扫描，没有外部输入面，要求令牌是多余的。这条保证了 T06 不会破坏既有单体宿主。
   */
  @Test
  void inProcessRegistrationNeedsNoTokenInMonolith() {
    RegistrationGuard guard = new RegistrationGuard(null);
    assertThatCode(() -> guard.check(inProcessManifest(), null)).doesNotThrowAnyException();
  }

  /**
   * 无认证 Bean 时，http 注册**被拒**而不是放行。
   *
   * <p>这是本类最重要的一条：若缺 Bean 就放行远程注册，等于给未配置认证的部署留一个静默后门。 「没配认证」的正确含义是「不接受远程注册」，不是「不检查」。
   */
  @Test
  void httpRegistrationIsDeniedWhenNoAuthConfigured() {
    RegistrationGuard guard = new RegistrationGuard(null);
    assertThatThrownBy(() -> guard.check(httpManifest(SERVICE), TOKEN))
        .isInstanceOf(RegistrationGuard.Denied.class)
        .hasMessageContaining("requires provider auth");
  }

  // ---------------------------------------------------------------- 微服务形态

  @Test
  void httpRegistrationWithValidTokenIsAllowed() {
    RegistrationGuard guard = new RegistrationGuard(auth());
    assertThatCode(() -> guard.check(httpManifest(SERVICE), TOKEN)).doesNotThrowAnyException();
  }

  @Test
  void httpRegistrationWithoutTokenIsDenied() {
    RegistrationGuard guard = new RegistrationGuard(auth());
    assertThatThrownBy(() -> guard.check(httpManifest(SERVICE), null))
        .isInstanceOf(RegistrationGuard.Denied.class)
        .hasMessageContaining("invalid provider token");
  }

  @Test
  void httpRegistrationWithWrongTokenIsDenied() {
    RegistrationGuard guard = new RegistrationGuard(auth());
    assertThatThrownBy(() -> guard.check(httpManifest(SERVICE), "wrong"))
        .isInstanceOf(RegistrationGuard.Denied.class);
  }

  /** 服务 A 的密钥不能注册服务 B 的工具（令牌与 serviceName 绑定）。 */
  @Test
  void tokenOfOneServiceCannotRegisterAnotherService() {
    ProviderAuth multi =
        new SharedSecretProviderAuth(
            Map.of("order-service", "token-order", "refund-service", "token-refund"));
    RegistrationGuard guard = new RegistrationGuard(multi);

    assertThatCode(() -> guard.check(httpManifest("order-service"), "token-order"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> guard.check(httpManifest("refund-service"), "token-order"))
        .isInstanceOf(RegistrationGuard.Denied.class);
  }

  /** 缺 serviceName 无法绑定令牌 → 拒绝（契约的 if/then 也拦，这里先拦）。 */
  @Test
  void httpRegistrationWithoutServiceNameIsDenied() {
    RegistrationGuard guard = new RegistrationGuard(auth());
    ObjectNode m = MAPPER.createObjectNode();
    m.put("toolId", "order.detail.get").put("version", "1.0.0").put("protocol", "http");
    assertThatThrownBy(() -> guard.check(m, TOKEN))
        .isInstanceOf(RegistrationGuard.Denied.class)
        .hasMessageContaining("serviceName is required");
  }

  /** 拒绝消息不得回显令牌内容。 */
  @Test
  void denialMessageDoesNotLeakToken() {
    RegistrationGuard guard = new RegistrationGuard(auth());
    assertThatThrownBy(() -> guard.check(httpManifest(SERVICE), "super-secret-value"))
        .isInstanceOf(RegistrationGuard.Denied.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("super-secret-value"));
  }

  /** 协议缺失 / 未知：本守卫放过，交后续契约校验判定（职责单一）。 */
  @Test
  void unknownProtocolIsLeftToContractValidation() {
    RegistrationGuard guard = new RegistrationGuard(auth());
    ObjectNode m = MAPPER.createObjectNode().put("toolId", "x.y.z").put("version", "1.0.0");
    assertThatCode(() -> guard.check(m, null)).doesNotThrowAnyException();
  }
}
