package com.strato.domain.refund.domain;

import java.util.Optional;

/** 退款域查看订单的端口。领域模块之间互不依赖，首期由 app 或 refund-service 自己的 infra 提供一个读订单快照的实现。 只暴露退款决策需要的最小字段。 */
public interface OrderLookup {
  Optional<EligibilityPolicy.Snapshot> snapshot(String tenantId, String orderId);
}
