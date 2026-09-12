package com.sparkrooter.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 调用方认证（feat-provider-http-transport §1.2 / §3.5）。
 *
 * <p>这些断言守的是一个真实漏洞：hub 的注册端点原本无认证，跨服务后任何调用方都能注册伪造工具 （例如声称 {@code sideEffect=false}
 * 的假查询工具实际执行转账）。契约层的 {@code high ⇒ required} 拦不住「低风险伪装」，所以认证是前置必要条件而非可选增强。
 */
final class SharedSecretProviderAuthTest {

  private static final String SERVICE = "order-service";
  private static final String TOKEN = "s3cret-order";

  private ProviderAuth auth() {
    return new SharedSecretProviderAuth(Map.of(SERVICE, TOKEN));
  }

  @Test
  void acceptsMatchingServiceAndToken() {
    assertThat(auth().verify(SERVICE, TOKEN)).isTrue();
  }

  @Test
  void rejectsWrongToken() {
    assertThat(auth().verify(SERVICE, "wrong")).isFalse();
  }

  @Test
  void rejectsMissingToken() {
    assertThat(auth().verify(SERVICE, null)).isFalse();
  }

  /**
   * 服务 A 的密钥不得用于注册服务 B 的工具。
   *
   * <p>只校验「令牌在已知集合里」是不够的——那样任一 provider 被攻破即可冒充所有其他 provider， 注册伪造的高危工具。令牌必须与声称的 serviceName 绑定。
   */
  @Test
  void rejectsValidTokenPresentedForAnotherService() {
    Map<String, String> tokens = new HashMap<>();
    tokens.put("order-service", "token-order");
    tokens.put("refund-service", "token-refund");
    ProviderAuth auth = new SharedSecretProviderAuth(tokens);

    assertThat(auth.verify("order-service", "token-order")).isTrue();
    // order 的密钥拿去冒充 refund → 必须拒绝
    assertThat(auth.verify("refund-service", "token-order")).isFalse();
  }

  @Test
  void rejectsUnknownService() {
    assertThat(auth().verify("ghost-service", TOKEN)).isFalse();
  }

  /** 未知服务与错误密钥都走同一条比较路径，不因「是否立即返回」泄漏服务名是否存在。 */
  @Test
  void unknownServiceDoesNotShortCircuitDifferentlyFromWrongToken() {
    ProviderAuth auth = auth();
    assertThat(auth.verify("ghost-service", "anything")).isFalse();
    assertThat(auth.verify(SERVICE, "anything")).isFalse();
  }

  // ---------------------------------------------------------------- fail-fast

  /** 空映射 → 拒绝构造。安全相关的配置缺失必须启动失败，不能悄悄放行。 */
  @Test
  void emptyTokenMapRefusesToStart() {
    assertThatThrownBy(() -> new SharedSecretProviderAuth(Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("refusing to start");
  }

  @Test
  void nullTokenMapRefusesToStart() {
    assertThatThrownBy(() -> new SharedSecretProviderAuth(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("refusing to start");
  }

  @Test
  void blankTokenRefusesToStart() {
    Map<String, String> tokens = new HashMap<>();
    tokens.put(SERVICE, "  ");
    assertThatThrownBy(() -> new SharedSecretProviderAuth(tokens))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("blank token");
  }

  @Test
  void blankServiceNameRefusesToStart() {
    Map<String, String> tokens = new HashMap<>();
    tokens.put(" ", TOKEN);
    assertThatThrownBy(() -> new SharedSecretProviderAuth(tokens))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("blank serviceName");
  }

  // ---------------------------------------------------------------- 常量时间比较

  /**
   * 比较必须走 {@code MessageDigest.isEqual} 而非 {@code String.equals}。
   *
   * <p>无法用计时断言证明常量时间（JIT / 噪音会让测试不稳定），故断言可观察的等价行为： 长度不同、仅首字节不同、仅末字节不同都返回 false，且 null
   * 安全。真正的保证来自实现选择， 这里守的是「有人把它改回 equals 时至少行为语义不变」的基线。
   */
  @Test
  void constantTimeEqualsHandlesEdgeCases() {
    assertThat(ProviderAuth.constantTimeEquals("abc", "abc")).isTrue();
    assertThat(ProviderAuth.constantTimeEquals("abc", "abd")).isFalse();
    assertThat(ProviderAuth.constantTimeEquals("abc", "bbc")).isFalse();
    assertThat(ProviderAuth.constantTimeEquals("abc", "abcd")).isFalse();
    assertThat(ProviderAuth.constantTimeEquals("", "")).isTrue();
    assertThat(ProviderAuth.constantTimeEquals(null, "abc")).isFalse();
    assertThat(ProviderAuth.constantTimeEquals("abc", null)).isFalse();
    assertThat(ProviderAuth.constantTimeEquals(null, null)).isFalse();
  }

  /** 非 ASCII 密钥按 UTF-8 字节比较，不因编码差异误判。 */
  @Test
  void constantTimeEqualsIsUtf8Safe() {
    assertThat(ProviderAuth.constantTimeEquals("密钥-A", "密钥-A")).isTrue();
    assertThat(ProviderAuth.constantTimeEquals("密钥-A", "密钥-B")).isFalse();
  }

  /** 出站令牌按被调服务取：hub 调多个 provider，各自密钥不同。 */
  @Test
  void outboundTokenIsPerService() {
    Map<String, String> tokens = new HashMap<>();
    tokens.put("order-service", "token-order");
    tokens.put("refund-service", "token-refund");
    ProviderAuth auth = new SharedSecretProviderAuth(tokens);

    assertThat(auth.outboundToken("order-service")).isEqualTo("token-order");
    assertThat(auth.outboundToken("refund-service")).isEqualTo("token-refund");
    assertThat(auth.outboundToken("ghost-service")).as("未知服务无令牌可用").isNull();
    assertThat(auth.outboundToken(null)).isNull();
  }
}
