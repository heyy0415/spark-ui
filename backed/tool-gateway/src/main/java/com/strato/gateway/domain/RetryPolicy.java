package com.strato.gateway.domain;

import com.strato.contracts.model.ToolManifest;

/**
 * 重试策略（纯函数，backend-standard §6 / agent-safety §5）： 只有幂等（idempotency = required）或无副作用（sideEffect =
 * false）的工具才允许按 execution.maxRetries 重试。
 */
public final class RetryPolicy {

  private RetryPolicy() {}

  public static int allowedRetries(ToolManifest m) {
    boolean safe =
        m.execution().idempotency() == ToolManifest.Idempotency.required || !m.risk().sideEffect();
    return safe ? Math.max(0, m.execution().maxRetries()) : 0;
  }
}
