package com.strato.domain.order.domain;

import java.util.List;
import java.util.Optional;

/** 订单仓储端口；无框架依赖。 */
public interface OrderRepository {
  Optional<Order> find(String tenantId, String orderId);

  List<Order> findByTenant(String tenantId);

  void save(Order order);
}
