package com.strato.domain.order.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.strato.domain.order.domain.Order;
import com.strato.domain.order.domain.OrderRepository;
import com.strato.spi.ExecutionContext;
import com.strato.spi.ToolHandler;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * order.list.search@1.1.0：按 createdAt 降序（仓储保证），排除 DELETED，limit 默认 20；total 为过滤后的总数（不受 limit 影响）。
 */
@Component
public class OrderListSearchHandler implements ToolHandler {

  private static final int DEFAULT_LIMIT = 20;

  private final OrderRepository orders;
  private final OrderJson json;
  private final ObjectMapper mapper;

  public OrderListSearchHandler(OrderRepository orders, OrderJson json, ObjectMapper mapper) {
    this.orders = orders;
    this.json = json;
    this.mapper = mapper;
  }

  @Override
  public String toolId() {
    return "order.list.search";
  }

  @Override
  public String version() {
    return "1.1.0";
  }

  @Override
  public JsonNode handle(JsonNode args, ExecutionContext ctx) {
    String status = args.hasNonNull("status") ? args.get("status").asText() : null;
    int limit = args.hasNonNull("limit") ? args.get("limit").asInt() : DEFAULT_LIMIT;
    List<Order> matched =
        orders.findByTenant(ctx.principal().tenantId()).stream()
            .filter(o -> o.status() != Order.OrderStatus.DELETED)
            .filter(o -> status == null || o.status().name().equals(status))
            .toList();
    ArrayNode items = mapper.createArrayNode();
    matched.stream().limit(limit).forEach(o -> items.add(json.listItem(o)));
    ObjectNode out = mapper.createObjectNode();
    out.set("items", items);
    out.put("total", matched.size());
    return out;
  }
}
