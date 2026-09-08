package com.strato.spi;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单只读快照：供其它领域的**策略层**（退款资格、售后资格）与只读工具输出摘要使用。 领域模块之间不互相 import，经本接口的实现（order-service 提供）读取。多商品单
 * productName = 首行商品名，quantity = 总件数。
 */
public record OrderSnapshot(
    String orderId,
    String status,
    BigDecimal amount,
    String currency,
    String productName,
    int quantity,
    Instant createdAt) {}
