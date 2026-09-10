package com.sparkrooter.examples.order.infra;

import com.sparkrooter.examples.order.domain.LogisticsEvent;
import com.sparkrooter.examples.order.domain.Order;
import com.sparkrooter.examples.order.domain.OrderItem;
import com.sparkrooter.examples.order.domain.OrderRepository;
import com.sparkrooter.spi.SeedLoader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * 从 classpath data/*.json 种子装配订单聚合的内存仓储（json 键 = MySQL 列名，与 schema.sql 一致）。 加载时校验：每单至少一行商品、Σ
 * line_amount == amount、物流 seq 连续；任一不符启动即失败，防止种子与 DDL 漂移。 写操作只改内存（软删），重启还原。后续换 MySQL / Redis 只替换本类。
 */
@Repository
public class SeededOrderRepository implements OrderRepository {

  private static final Logger log = LoggerFactory.getLogger(SeededOrderRepository.class);

  /** orders.json 行。 */
  record OrderRow(
      String orderId,
      String tenantId,
      String userId,
      String productName,
      String thumbnail,
      int quantity,
      BigDecimal amount,
      String currency,
      String status,
      String receiver,
      String phoneMasked,
      String region,
      Instant createdAt) {}

  /** order_items.json 行。 */
  record ItemRow(
      String itemId,
      String orderId,
      String productId,
      String productName,
      BigDecimal unitPrice,
      int quantity,
      BigDecimal lineAmount) {}

  /** logistics_events.json 行。 */
  record EventRow(
      String eventId,
      String orderId,
      String carrier,
      String trackingNo,
      int seq,
      Instant eventTime,
      String location,
      String description) {}

  private final Map<String, Order> store = new ConcurrentHashMap<>();

  public SeededOrderRepository() {
    List<OrderRow> rows = SeedLoader.load("data/orders.json", OrderRow.class);
    Map<String, List<ItemRow>> items =
        SeedLoader.load("data/order_items.json", ItemRow.class).stream()
            .collect(Collectors.groupingBy(ItemRow::orderId));
    Map<String, List<EventRow>> events =
        SeedLoader.load("data/logistics_events.json", EventRow.class).stream()
            .collect(Collectors.groupingBy(EventRow::orderId));
    for (OrderRow r : rows) {
      Order o =
          assemble(
              r,
              items.getOrDefault(r.orderId(), List.of()),
              events.getOrDefault(r.orderId(), List.of()));
      store.put(key(o.tenantId(), o.orderId()), o);
    }
    log.info(
        "order seed loaded orders={} items={} logisticsEvents={}",
        rows.size(),
        items.values().stream().mapToInt(List::size).sum(),
        events.values().stream().mapToInt(List::size).sum());
  }

  private static Order assemble(OrderRow r, List<ItemRow> itemRows, List<EventRow> eventRows) {
    if (itemRows.isEmpty()) {
      throw new IllegalStateException("order seed invalid: order " + r.orderId() + " has no items");
    }
    List<OrderItem> items =
        itemRows.stream()
            .sorted(Comparator.comparing(ItemRow::itemId))
            .map(
                i ->
                    new OrderItem(
                        i.itemId(),
                        i.productId(),
                        i.productName(),
                        i.unitPrice(),
                        i.quantity(),
                        i.lineAmount()))
            .toList();
    BigDecimal sum =
        items.stream().map(OrderItem::lineAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (sum.compareTo(r.amount()) != 0) {
      throw new IllegalStateException(
          "order seed invalid: order "
              + r.orderId()
              + " amount "
              + r.amount()
              + " != sum(items) "
              + sum);
    }
    List<LogisticsEvent> logistics =
        eventRows.stream()
            .sorted(Comparator.comparingInt(EventRow::seq))
            .map(
                e ->
                    new LogisticsEvent(
                        e.eventId(),
                        e.carrier(),
                        e.trackingNo(),
                        e.seq(),
                        e.eventTime(),
                        e.location(),
                        e.description()))
            .toList();
    for (int i = 0; i < logistics.size(); i++) {
      if (logistics.get(i).seq() != i + 1) {
        throw new IllegalStateException(
            "order seed invalid: order " + r.orderId() + " logistics seq gap");
      }
    }
    return new Order(
        r.orderId(),
        r.tenantId(),
        r.userId(),
        r.productName(),
        r.thumbnail(),
        r.quantity(),
        r.amount(),
        r.currency(),
        Order.OrderStatus.valueOf(r.status()),
        new Order.Address(r.receiver(), r.phoneMasked(), r.region()),
        r.createdAt(),
        items,
        logistics);
  }

  private static String key(String tenantId, String orderId) {
    return tenantId + "/" + orderId;
  }

  @Override
  public Optional<Order> find(String tenantId, String orderId) {
    return Optional.ofNullable(store.get(key(tenantId, orderId)));
  }

  /** 全部订单（含 DELETED），按 createdAt 降序。 */
  @Override
  public List<Order> findByTenant(String tenantId) {
    return store.values().stream()
        .filter(o -> o.tenantId().equals(tenantId))
        .sorted(Comparator.comparing(Order::createdAt).reversed())
        .toList();
  }

  @Override
  public void save(Order order) {
    store.put(key(order.tenantId(), order.orderId()), order);
  }
}
