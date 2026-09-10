package com.sparkrooter.examples.support;

import java.util.Optional;

/**
 * 订单快照端口（示例领域之间的跨域只读接口，属宿主 / 示例侧而非 spark 内核）：order-service 实现，refund / aftersale 的策略层消费。
 * 屏生成层不得调用（屏只用经 Gateway 的输出）。tenantId 是示例宿主的业务字段，内核对它一无所知。
 */
public interface OrderSnapshotProvider {
  Optional<OrderSnapshot> snapshot(String tenantId, String orderId);
}
