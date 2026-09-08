package com.strato.domain.order.domain;

import java.util.List;

/** 物流状态：由订单状态与最后一条轨迹事件派生，不单独存储。 */
public enum LogisticsStatus {
  /** 尚未发货（无物流事件）。 */
  NOT_SHIPPED,
  /** 运输中。 */
  IN_TRANSIT,
  /** 派送中（最后一条事件为「派送中」）。 */
  OUT_FOR_DELIVERY,
  /** 已签收（订单已完成，或最后一条事件为签收 / 确认）。 */
  DELIVERED;

  private static final String DELIVERING = "派送";
  private static final String SIGNED = "签收";
  private static final String CONFIRMED = "确认";

  /** 纯函数；events 须按 seq 升序。 */
  public static LogisticsStatus of(Order.OrderStatus status, List<LogisticsEvent> events) {
    if (events.isEmpty()) {
      return NOT_SHIPPED;
    }
    if (status == Order.OrderStatus.COMPLETED) {
      return DELIVERED;
    }
    String last = events.get(events.size() - 1).description();
    if (last.contains(SIGNED) || last.contains(CONFIRMED)) {
      return DELIVERED;
    }
    if (last.contains(DELIVERING)) {
      return OUT_FOR_DELIVERY;
    }
    return IN_TRANSIT;
  }
}
