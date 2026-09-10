package com.sparkrooter.examples.aftersale.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 售后创建策略（纯函数）：订单状态 ∈ {SHIPPED, COMPLETED} 且该订单没有进行中的售后单。 同一规则被 handler（第二道保险）与 runtime
 * 确认后的重校验（第一道）共用。
 */
public final class AftersalePolicy {

  private AftersalePolicy() {}

  private static final Set<String> ELIGIBLE_ORDER_STATUS = Set.of("SHIPPED", "COMPLETED");

  /** 允许申请售后的订单状态名（供以 String 形态做重校验的调用方使用）。 */
  public static Set<String> eligibleOrderStatuses() {
    return ELIGIBLE_ORDER_STATUS;
  }

  /** 空 = 允许创建；非空 = 拒绝原因（面向用户的中文）。 */
  public static Optional<String> reject(String orderStatus, List<Aftersale> existing) {
    if (!ELIGIBLE_ORDER_STATUS.contains(orderStatus)) {
      return Optional.of("订单状态不允许申请售后：" + orderStatus);
    }
    boolean hasActive = existing.stream().anyMatch(a -> a.status().active());
    if (hasActive) {
      return Optional.of("该订单已有进行中的售后单");
    }
    return Optional.empty();
  }
}
