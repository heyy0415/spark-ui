package com.strato.domain.order.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** 订单聚合（首期内存）。金额用 BigDecimal 运算，对外序列化为两位小数字符串。 */
public record Order(
    String orderId,
    String tenantId,
    String productName,
    BigDecimal amount,
    String currency,
    OrderStatus status,
    Instant createdAt) {

  public enum OrderStatus {
    PAID,
    SHIPPED,
    COMPLETED,
    REFUNDED,
    CANCELLED
  }

  /** 契约金额格式："128.00"。 */
  public String amountText() {
    return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
  }

  public Order withStatus(OrderStatus s) {
    return new Order(orderId, tenantId, productName, amount, currency, s, createdAt);
  }
}
