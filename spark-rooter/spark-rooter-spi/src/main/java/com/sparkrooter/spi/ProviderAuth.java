package com.sparkrooter.spi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * hub ↔ provider 的双向调用方认证。
 *
 * <p>两个方向都要认证：provider 向 hub 注册 Manifest（否则任何调用方都能注册伪造工具，包括声称 {@code sideEffect=false}
 * 的假查询工具实际执行转账）；hub 向 provider 发起执行（否则任何人都能驱动领域写操作）。
 *
 * <p>默认实现是配置化共享密钥。宿主要用 mTLS / 网关鉴权，定义同类型 Bean 即覆盖——与其他 spi 端口一致。
 *
 * <p><b>安全缺省不能是宽松的</b>：密钥未配置时装配方必须拒绝启动，而不是放行（与 {@code SessionIdResolver} 的既有决策一致）。单体形态不装配本端口，故不受影响。
 */
public interface ProviderAuth {

  /** 出站调用时放在 HTTP header 里的令牌名。 */
  String HEADER = "X-Spark-Provider-Token";

  /**
   * 校验入站令牌。
   *
   * @param serviceName 声称的服务名；实现应校验它与令牌对应的服务一致——只验「令牌有效」不够， 服务 A 的密钥不该能注册服务 B 的工具
   * @param token 入站令牌，可能为 null（缺 header）
   * @return true 通过
   */
  boolean verify(String serviceName, String token);

  /**
   * 调用指定服务时出示的令牌。
   *
   * <p><b>按服务取而非单一令牌</b>：hub 会调多个 provider，各自密钥不同；provider 只调 hub 一个对端。 一个无参的 {@code
   * outboundToken()} 表达不了前者——首版就是这么写的，导致 hub 侧只能给 一个"默认出站令牌"，调第二个 provider 必然 401。
   *
   * @param serviceName 被调服务的逻辑名
   * @return 令牌；未知服务返回 null（调用方应视为无法调用该服务）
   */
  String outboundToken(String serviceName);

  /**
   * 常量时间比较，防时序侧信道。
   *
   * <p>{@code String.equals} 在首个不同字节即返回，攻击者可按响应时间逐字节猜测令牌。 {@link MessageDigest#isEqual} 是 JDK
   * 提供的常量时间实现。
   */
  static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
