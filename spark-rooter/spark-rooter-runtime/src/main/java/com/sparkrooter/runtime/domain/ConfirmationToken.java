package com.sparkrooter.runtime.domain;

import java.time.Instant;
import java.util.Set;

/**
 * 确认令牌（agent-safety §3）：绑定 runId / actionId / 步骤 / 参数摘要 / conversationId / sessionId，一次性，10 分钟过期。
 * allowedFormKeys 来自当前屏 Form.props.fields[]，确认时 formData 只允许这些键。sessionId 由宿主 SessionIdResolver
 * 产出：知道会话号不等于能确认。
 */
public record ConfirmationToken(
    String token,
    String runId,
    String actionId,
    int stepSeq,
    String argsDigest,
    String conversationId,
    String sessionId,
    Set<String> allowedFormKeys,
    Instant expiresAt) {

  public ConfirmationToken {
    allowedFormKeys = Set.copyOf(allowedFormKeys);
  }

  public boolean expired(Instant now) {
    return !now.isBefore(expiresAt);
  }
}
