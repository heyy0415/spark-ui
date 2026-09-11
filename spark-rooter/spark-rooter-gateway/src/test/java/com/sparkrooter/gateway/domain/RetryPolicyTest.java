package com.sparkrooter.gateway.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.gateway.support.Manifests;
import org.junit.jupiter.api.Test;

/** 重试策略：只有幂等或无副作用的工具才允许按 maxRetries 重试；有副作用且非幂等一律 0。 */
final class RetryPolicyTest {

  @Test
  void readOnlyToolMayRetry() {
    ToolManifest m = Manifests.withExecution(false, ToolManifest.Idempotency.none, 2);
    assertThat(RetryPolicy.allowedRetries(m)).isEqualTo(2);
  }

  @Test
  void idempotentSideEffectToolMayRetry() {
    ToolManifest m = Manifests.withExecution(true, ToolManifest.Idempotency.required, 3);
    assertThat(RetryPolicy.allowedRetries(m)).isEqualTo(3);
  }

  @Test
  void nonIdempotentSideEffectNeverRetries() {
    // 契约 if/then 本会拒绝 sideEffect=true 且 idempotency=none 的 Manifest；这里绕过契约直接构造对象，验证策略本身 fail-safe
    ToolManifest m = Manifests.withExecution(true, ToolManifest.Idempotency.none, 5);
    assertThat(RetryPolicy.allowedRetries(m)).isZero();
  }

  @Test
  void negativeMaxRetriesClampsToZero() {
    ToolManifest m = Manifests.withExecution(false, ToolManifest.Idempotency.none, -1);
    assertThat(RetryPolicy.allowedRetries(m)).isZero();
  }
}
