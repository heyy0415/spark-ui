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

/** order.list.search@1.0.0。 */
@Component
public class OrderListSearchHandler implements ToolHandler {

  private static final int DEFAULT_LIMIT = 20;

  private final OrderRepository orders;
  private final ObjectMapper mapper;

  public OrderListSearchHandler(OrderRepository orders, ObjectMapper mapper) {
    this.orders = orders;
    this.mapper = mapper;
  }

  @Override
  public String toolId() {
    return "order.list.search";
  }

  @Override
  public String version() {
    return "1.0.0";
  }

  @Override
  public JsonNode handle(JsonNode args, ExecutionContext ctx) {
    String status = args.hasNonNull("status") ? args.get("status").asText() : null;
    int limit = args.hasNonNull("limit") ? args.get("limit").asInt() : DEFAULT_LIMIT;
    List<Order> all = orders.findByTenant(ctx.principal().tenantId());
    List<Order> matched =
        all.stream().filter(o -> status == null || o.status().name().equals(status)).toList();
    ArrayNode items = mapper.createArrayNode();
    matched.stream()
        .limit(limit)
        .forEach(
            o -> {
              ObjectNode n = items.addObject();
              n.put("orderId", o.orderId());
              n.put("productName", o.productName());
              n.put("amount", o.amountText());
              n.put("status", o.status().name());
            });
    ObjectNode out = mapper.createObjectNode();
    out.set("items", items);
    out.put("total", matched.size());
    return out;
  }
}
