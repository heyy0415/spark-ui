package com.strato.domain.order.infra.screen;

import com.fasterxml.jackson.databind.JsonNode;
import com.strato.domain.order.domain.DeletionPolicy;
import com.strato.domain.order.domain.Order;
import com.strato.spi.ConfirmationRecheck;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * order.delete 确认后重校验：经 Gateway 重调 order.detail.get，DeletionPolicy 不允许该状态 → 拒绝（第一道保险；handler
 * 自身是第二道）。
 */
@Component
public class OrderDeleteRecheck implements ConfirmationRecheck {

  @Override
  public String toolId() {
    return "order.delete";
  }

  @Override
  public String recheckToolId() {
    return "order.detail.get";
  }

  @Override
  public Map<String, String> recheckArgs(Map<String, String> fixedArgs) {
    return Map.of("orderId", fixedArgs.getOrDefault("orderId", ""));
  }

  @Override
  public Optional<String> reject(JsonNode recheckOutput, JsonNode shownUi) {
    String status = recheckOutput.path("status").asText("");
    Order.OrderStatus st;
    try {
      st = Order.OrderStatus.valueOf(status);
    } catch (IllegalArgumentException e) {
      return Optional.of("unknown order status: " + status);
    }
    return DeletionPolicy.reject(st);
  }

  @Override
  public Map<String, String> trustedArgs(JsonNode recheckOutput) {
    return Map.of();
  }
}
