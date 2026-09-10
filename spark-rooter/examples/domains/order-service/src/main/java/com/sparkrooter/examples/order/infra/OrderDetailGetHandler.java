package com.sparkrooter.examples.order.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparkrooter.examples.order.domain.Order;
import com.sparkrooter.examples.order.domain.OrderRepository;
import com.sparkrooter.spi.ExecutionContext;
import com.sparkrooter.spi.ToolHandler;
import org.springframework.stereotype.Component;

/** order.detail.get@1.1.0。DELETED 订单视为业务错误（Gateway → HANDLER_ERROR），不泄露已删除内容。 */
@Component
public class OrderDetailGetHandler implements ToolHandler {

  private final OrderRepository orders;
  private final OrderJson json;

  public OrderDetailGetHandler(OrderRepository orders, OrderJson json) {
    this.orders = orders;
    this.json = json;
  }

  @Override
  public String toolId() {
    return "order.detail.get";
  }

  @Override
  public String version() {
    return "1.1.0";
  }

  @Override
  public JsonNode handle(JsonNode args, ExecutionContext ctx) {
    Order o = OrderAccess.require(orders, ctx.principal().tenantId(), args.get("orderId").asText());
    return json.detail(o);
  }
}
