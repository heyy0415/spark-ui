package com.sparkrooter.examples.order.infra;

import com.sparkrooter.examples.order.domain.Order;
import com.sparkrooter.examples.order.domain.OrderRepository;
import com.sparkrooter.examples.support.OrderSnapshot;
import com.sparkrooter.examples.support.OrderSnapshotProvider;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** spi OrderSnapshotProvider 实现：给 refund / aftersale 的领域策略用。DELETED 订单视为不存在。 */
@Component
public class OrderSnapshotAdapter implements OrderSnapshotProvider {

  private final OrderRepository orders;

  public OrderSnapshotAdapter(OrderRepository orders) {
    this.orders = orders;
  }

  @Override
  public Optional<OrderSnapshot> snapshot(String tenantId, String orderId) {
    return orders
        .find(tenantId, orderId)
        .filter(o -> o.status() != Order.OrderStatus.DELETED)
        .map(
            o ->
                new OrderSnapshot(
                    o.orderId(),
                    o.status().name(),
                    o.amount(),
                    o.currency(),
                    o.productName(),
                    o.quantity(),
                    o.createdAt()));
  }
}
