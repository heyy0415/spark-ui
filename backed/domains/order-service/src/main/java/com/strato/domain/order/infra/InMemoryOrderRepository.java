package com.strato.domain.order.infra;

import com.strato.domain.order.domain.Order;
import com.strato.domain.order.domain.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/** 内存订单仓储，预置 3 条 tenant_001 订单。 10001 / 10002 供验收链路使用，10003 保留给启动自检（spec §2.2）。 */
@Repository
public class InMemoryOrderRepository implements OrderRepository {

  private final Map<String, Order> store = new ConcurrentHashMap<>();

  public InMemoryOrderRepository() {
    seed("10001", "示例商品", "128.00", Order.OrderStatus.PAID, "2026-08-30T02:15:00Z");
    seed("10002", "蓝牙耳机", "299.00", Order.OrderStatus.SHIPPED, "2026-09-01T09:40:00Z");
    seed("10003", "自检专用商品", "1.00", Order.OrderStatus.PAID, "2026-09-02T00:00:00Z");
  }

  private void seed(String id, String name, String amount, Order.OrderStatus st, String at) {
    store.put(
        key("tenant_001", id),
        new Order(id, "tenant_001", name, new BigDecimal(amount), "CNY", st, Instant.parse(at)));
  }

  private static String key(String tenantId, String orderId) {
    return tenantId + "/" + orderId;
  }

  @Override
  public Optional<Order> find(String tenantId, String orderId) {
    return Optional.ofNullable(store.get(key(tenantId, orderId)));
  }

  @Override
  public List<Order> findByTenant(String tenantId) {
    return store.values().stream()
        .filter(o -> o.tenantId().equals(tenantId))
        .sorted((a, b) -> a.orderId().compareTo(b.orderId()))
        .toList();
  }

  @Override
  public void save(Order order) {
    store.put(key(order.tenantId(), order.orderId()), order);
  }
}
