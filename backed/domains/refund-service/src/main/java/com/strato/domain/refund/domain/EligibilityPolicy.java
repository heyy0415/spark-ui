package com.strato.domain.refund.domain;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 退款资格规则（纯函数）。首期规则：订单状态为 PAID 或 SHIPPED 且尚无退款单时可退，可退金额为订单全额。 refund-service 不能 import
 * order-service（同为领域模块，互不依赖），因此订单信息由 OrderLookup 端口提供。
 */
public final class EligibilityPolicy {

  private EligibilityPolicy() {}

  public record Snapshot(String orderId, String status, BigDecimal amount) {}

  public record Result(boolean eligible, BigDecimal refundableAmount, Optional<String> reason) {}

  public static Result evaluate(Snapshot order, boolean alreadyRefunded) {
    if (alreadyRefunded) {
      return new Result(false, BigDecimal.ZERO, Optional.of("订单已存在退款单"));
    }
    boolean statusOk = "PAID".equals(order.status()) || "SHIPPED".equals(order.status());
    if (!statusOk) {
      return new Result(false, BigDecimal.ZERO, Optional.of("订单状态不允许退款：" + order.status()));
    }
    return new Result(true, order.amount(), Optional.empty());
  }
}
