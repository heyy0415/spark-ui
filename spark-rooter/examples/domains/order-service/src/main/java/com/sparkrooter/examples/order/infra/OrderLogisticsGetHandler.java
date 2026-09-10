package com.sparkrooter.examples.order.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparkrooter.examples.order.domain.Order;
import com.sparkrooter.examples.order.domain.OrderRepository;
import com.sparkrooter.examples.support.DemoUserContext;
import com.sparkrooter.spi.ExecutionContext;
import com.sparkrooter.spi.ToolHandler;
import org.springframework.stereotype.Component;

/** order.logistics.get@1.0.0。无物流的订单（PAID / CANCELLED）不报错，返回 NOT_SHIPPED + 空 events。 */
@Component
public class OrderLogisticsGetHandler implements ToolHandler {

  private final OrderRepository orders;
  private final OrderJson json;

  public OrderLogisticsGetHandler(OrderRepository orders, OrderJson json) {
    this.orders = orders;
    this.json = json;
  }

  @Override
  public String toolId() {
    return "order.logistics.get";
  }

  @Override
  public String version() {
    return "1.0.0";
  }

  @Override
  public JsonNode handle(JsonNode args, ExecutionContext ctx) {
    Order o = OrderAccess.require(orders, DemoUserContext.tenantId(), args.get("orderId").asText());
    return json.logistics(o);
  }
}
