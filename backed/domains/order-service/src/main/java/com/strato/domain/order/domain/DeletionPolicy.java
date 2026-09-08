package com.strato.domain.order.domain;

import java.util.Optional;
import java.util.Set;

/**
 * 订单删除策略（纯函数，无框架依赖）。只允许终态订单软删：COMPLETED / CANCELLED / REFUNDED；PAID / SHIPPED 仍在履约中，拒绝并给出业务原因。
 * 同一规则被 handler（第二道保险）与 runtime 确认后的重校验（第一道）共用。
 */
public final class DeletionPolicy {

  private DeletionPolicy() {}

  private static final Set<Order.OrderStatus> DELETABLE =
      Set.of(Order.OrderStatus.COMPLETED, Order.OrderStatus.CANCELLED, Order.OrderStatus.REFUNDED);

  /** 允许删除的状态名集合（供以 String 形态做重校验的调用方使用）。 */
  public static Set<String> deletableStatusNames() {
    return Set.of("COMPLETED", "CANCELLED", "REFUNDED");
  }

  /** 空 = 允许删除；非空 = 拒绝原因（面向用户的中文）。 */
  public static Optional<String> reject(Order.OrderStatus status) {
    if (status == Order.OrderStatus.DELETED) {
      return Optional.of("订单已删除");
    }
    if (!DELETABLE.contains(status)) {
      return Optional.of("订单状态不允许删除：" + status);
    }
    return Optional.empty();
  }
}
