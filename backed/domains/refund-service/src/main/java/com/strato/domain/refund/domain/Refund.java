package com.strato.domain.refund.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** 退款单聚合（首期内存）。 */
public record Refund(
    String refundId,
    String tenantId,
    String orderId,
    BigDecimal amount,
    String currency,
    String reason,
    RefundStatus status,
    String idempotencyKey,
    Instant createdAt) {

  public enum RefundStatus {
    SUBMITTED,
    PROCESSING,
    COMPLETED,
    REJECTED
  }

  public String amountText() {
    return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
  }
}
