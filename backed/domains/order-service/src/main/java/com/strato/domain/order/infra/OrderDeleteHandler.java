package com.strato.domain.order.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.strato.domain.order.domain.DeletionPolicy;
import com.strato.domain.order.domain.Order;
import com.strato.domain.order.domain.OrderRepository;
import com.strato.spi.ExecutionContext;
import com.strato.spi.ToolHandler;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * order.delete@1.0.0：软删（status → DELETED）。handler 自身再过一次 DeletionPolicy 作第二道保险（第一道是 runtime
 * 确认后的重校验）；幂等由 Gateway 按 idempotencyKey 保证，本类不重试。
 */
@Component
public class OrderDeleteHandler implements ToolHandler {

  private static final Logger log = LoggerFactory.getLogger(OrderDeleteHandler.class);

  private final OrderRepository orders;
  private final ObjectMapper mapper;

  public OrderDeleteHandler(OrderRepository orders, ObjectMapper mapper) {
    this.orders = orders;
    this.mapper = mapper;
  }

  @Override
  public String toolId() {
    return "order.delete";
  }

  @Override
  public String version() {
    return "1.0.0";
  }

  @Override
  public JsonNode handle(JsonNode args, ExecutionContext ctx) {
    String tenantId = ctx.principal().tenantId();
    String orderId = args.get("orderId").asText();
    Order o =
        orders
            .find(tenantId, orderId)
            .orElseThrow(() -> new IllegalArgumentException("order not found: " + orderId));
    DeletionPolicy.reject(o.status())
        .ifPresent(
            reason -> {
              throw new IllegalStateException(reason);
            });
    Instant now = Instant.now();
    orders.save(o.withStatus(Order.OrderStatus.DELETED));
    log.info("order deleted orderId={} tenant={} runId={}", orderId, tenantId, ctx.runId());
    ObjectNode out = mapper.createObjectNode();
    out.put("orderId", orderId);
    out.put("deleted", true);
    out.put("deletedAt", now.toString());
    return out;
  }
}
