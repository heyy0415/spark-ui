package com.strato.domain.refund.infra;

import com.strato.domain.refund.domain.EligibilityPolicy;
import com.strato.domain.refund.domain.OrderLookup;
import com.strato.spi.OrderSnapshotProvider;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** OrderLookup 适配：委托 spi OrderSnapshotProvider（order-service 实现，app 装配），退款域不 import 订单域。 */
@Component
public class SnapshotOrderLookup implements OrderLookup {

  private final OrderSnapshotProvider orders;

  public SnapshotOrderLookup(OrderSnapshotProvider orders) {
    this.orders = orders;
  }

  @Override
  public Optional<EligibilityPolicy.Snapshot> snapshot(String tenantId, String orderId) {
    return orders
        .snapshot(tenantId, orderId)
        .map(s -> new EligibilityPolicy.Snapshot(s.orderId(), s.status(), s.amount()));
  }
}
