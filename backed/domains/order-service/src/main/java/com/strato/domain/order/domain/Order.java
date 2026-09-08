package com.strato.domain.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * 订单聚合：订单头 + 商品行 + 物流事件（按 seq 升序）。金额用 BigDecimal 运算，对外序列化为两位小数字符串。 `productName` 为首行商品名（多商品单带「等 N
 * 件」），`quantity` 为总件数。软删：`status = DELETED`，列表默认不含。
 */
public record Order(
    String orderId,
    String tenantId,
    String userId,
    String productName,
    String thumbnail,
    int quantity,
    BigDecimal amount,
    String currency,
    OrderStatus status,
    Address address,
    Instant createdAt,
    List<OrderItem> items,
    List<LogisticsEvent> logistics) {

  /** 订单状态；DELETED 为软删终态，仅内部可见（列表排除、详情报错）。 */
  public enum OrderStatus {
    /** 已支付未发货。 */
    PAID,
    /** 已发货在途。 */
    SHIPPED,
    /** 已签收完成。 */
    COMPLETED,
    /** 已退款。 */
    REFUNDED,
    /** 已取消。 */
    CANCELLED,
    /** 已删除（软删）。 */
    DELETED
  }

  /** 收货地址；手机号只保留脱敏形式。 */
  public record Address(String receiver, String phoneMasked, String region) {}

  public Order {
    items = List.copyOf(items);
    logistics = List.copyOf(logistics);
  }

  /** 契约金额格式："128.00"。 */
  public String amountText() {
    return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  public Order withStatus(OrderStatus s) {
    return new Order(
        orderId,
        tenantId,
        userId,
        productName,
        thumbnail,
        quantity,
        amount,
        currency,
        s,
        address,
        createdAt,
        items,
        logistics);
  }

  /** 物流状态（派生值）。 */
  public LogisticsStatus logisticsStatus() {
    return LogisticsStatus.of(status, logistics);
  }
}
