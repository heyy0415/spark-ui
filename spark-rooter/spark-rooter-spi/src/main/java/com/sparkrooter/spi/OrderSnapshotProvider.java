package com.sparkrooter.spi;

import java.util.Optional;

/** 订单快照端口；order-service 实现，refund / aftersale 的策略层消费。屏生成层不得调用（屏只用经 Gateway 的输出）。 */
public interface OrderSnapshotProvider {
  Optional<OrderSnapshot> snapshot(String tenantId, String orderId);
}
