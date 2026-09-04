package com.strato.domain.order.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.strato.domain.order.domain.Order;
import com.strato.domain.order.domain.OrderRepository;
import com.strato.spi.ExecutionContext;
import com.strato.spi.ToolHandler;
import org.springframework.stereotype.Component;

/** order.detail.get@1.0.0。参数已由 Gateway 按 inputSchema 校验；订单不存在返回错误对象由 Gateway 转 HANDLER_ERROR。 */
@Component
public class OrderDetailGetHandler implements ToolHandler {

  private final OrderRepository orders;
  private final ObjectMapper mapper;

  public OrderDetailGetHandler(OrderRepository orders, ObjectMapper mapper) {
    this.orders = orders;
    this.mapper = mapper;
  }

  @Override
  public String toolId() {
    return "order.detail.get";
  }

  @Override
  public String version() {
    return "1.0.0";
  }

  @Override
  public JsonNode handle(JsonNode args, ExecutionContext ctx) {
    String orderId = args.get("orderId").asText();
    Order o =
        orders
            .find(ctx.principal().tenantId(), orderId)
            .orElseThrow(() -> new IllegalArgumentException("order not found: " + orderId));
    return toJson(o);
  }

  ObjectNode toJson(Order o) {
    ObjectNode n = mapper.createObjectNode();
    n.put("orderId", o.orderId());
    n.put("productName", o.productName());
    n.put("amount", o.amountText());
    n.put("currency", o.currency());
    n.put("status", o.status().name());
    n.put("createdAt", o.createdAt().toString());
    return n;
  }
}
