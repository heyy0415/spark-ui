package com.sparkrooter.runtime.application;

import com.sparkrooter.runtime.domain.ConfirmationToken;
import com.sparkrooter.runtime.domain.ConfirmationTokenStore;
import com.sparkrooter.runtime.domain.RunFailure;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * 确认令牌服务（agent-safety §3）：随机不透明 token；绑定 runId / actionId / 步骤 / argsDigest / conversationId /
 * sessionId / formData 键白名单；10 分钟过期；一次性（consume 即删）。 任一校验失败抛 CONFIRMATION_REJECTED，且不泄露具体哪一项失败给前端。
 */
public class ConfirmationTokenService {

  /** 默认有效期；starter 经 spark.runtime.token-ttl 覆盖。 */
  static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

  private static final SecureRandom RANDOM = new SecureRandom();

  private final ConfirmationTokenStore store;
  private final Clock clock;
  private final Duration ttl;

  public ConfirmationTokenService(ConfirmationTokenStore store, Clock clock) {
    this(store, clock, DEFAULT_TTL);
  }

  public ConfirmationTokenService(ConfirmationTokenStore store, Clock clock, Duration ttl) {
    this.store = store;
    this.clock = clock;
    this.ttl = ttl;
  }

  public ConfirmationToken issue(
      String runId,
      String actionId,
      int stepSeq,
      String argsDigest,
      String conversationId,
      String sessionId,
      Set<String> allowedFormKeys) {
    byte[] bytes = new byte[24];
    RANDOM.nextBytes(bytes);
    String token = "ct_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    ConfirmationToken t =
        new ConfirmationToken(
            token,
            runId,
            actionId,
            stepSeq,
            argsDigest,
            conversationId,
            sessionId,
            allowedFormKeys,
            Instant.now(clock).plus(ttl));
    store.put(t);
    return t;
  }

  /**
   * 取出并校验；成功返回令牌（已从存储移除，不可再用）。 校验项：存在、未过期、runId 与 actionId 匹配、argsDigest 与当前计划一致、conversationId 与
   * sessionId 都与签发时一致、formData 键全部在白名单内。
   */
  public ConfirmationToken consume(
      String rawToken,
      String runId,
      String actionId,
      String expectedArgsDigest,
      String conversationId,
      String sessionId,
      Map<String, Object> formData) {
    ConfirmationToken t =
        store
            .consume(rawToken)
            .orElseThrow(() -> new TokenUnknown("token unknown or already used"));
    Instant now = Instant.now(clock);
    if (t.expired(now)) {
      throw reject("token expired");
    }
    if (!t.runId().equals(runId) || !t.actionId().equals(actionId)) {
      throw reject("token bound to different run/action");
    }
    if (!t.argsDigest().equals(expectedArgsDigest)) {
      throw reject("plan arguments changed since token issued");
    }
    // 双绑定：会话号可被前端伪造，sessionId 来自宿主；任一不一致都拒绝
    if (!t.conversationId().equals(conversationId) || !t.sessionId().equals(sessionId)) {
      throw reject("token bound to different conversation/session");
    }
    for (String k : formData.keySet()) {
      if (!t.allowedFormKeys().contains(k)) {
        throw reject("formData key not allowed: " + k);
      }
    }
    return t;
  }

  /** 令牌不存在或已被消费（重放 / 伪造）。与其它拒绝分开：此时没有任何令牌被消费， Run 状态不应因这类请求改变（并发重放不能把执行中的 Run 打成 FAILED）。 */
  public static class TokenUnknown extends RunFailure {
    private static final long serialVersionUID = 1L;

    TokenUnknown(String internalReason) {
      super("CONFIRMATION_REJECTED", internalReason);
    }
  }

  private static RunFailure reject(String internalReason) {
    // 对外统一文案，内部原因只进日志（由调用方记录）
    return new RunFailure("CONFIRMATION_REJECTED", internalReason);
  }
}
