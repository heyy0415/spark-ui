package com.strato.domain.order.infra;

import com.strato.domain.order.domain.Order;
import com.strato.domain.order.domain.OrderRepository;

/** 三个读 handler 共用的取单逻辑：不存在或已软删都按业务错误抛出（Gateway 统一映射 HANDLER_ERROR）。 */
final class OrderAccess {

  private OrderAccess() {}

  static Order require(OrderRepository orders, String tenantId, String orderId) {
    Order o =
        orders
            .find(tenantId, orderId)
            .orElseThrow(() -> new IllegalArgumentException("order not found: " + orderId));
    if (o.status() == Order.OrderStatus.DELETED) {
      throw new IllegalStateException("订单已删除: " + orderId);
    }
    return o;
  }
}
