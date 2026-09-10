package com.sparkrooter.examples.order.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.examples.order.domain.LogisticsEvent;
import com.sparkrooter.examples.order.domain.Order;
import java.util.List;
import org.springframework.stereotype.Component;

/** 订单 → 工具输出 JSON 的唯一投影点；字段名与 tool-manifests/*.json outputSchema 一一对应。 */
@Component
public class OrderJson {

  private final ObjectMapper mapper;

  public OrderJson(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** order.list.search 行（1.1.0）。 */
  public ObjectNode listItem(Order o) {
    ObjectNode n = mapper.createObjectNode();
    n.put("orderId", o.orderId());
    n.put("productId", o.items().get(0).productId());
    n.put("productName", o.productName());
    n.put("thumbnail", o.thumbnail());
    n.put("quantity", o.quantity());
    n.put("amount", o.amountText());
    n.put("currency", o.currency());
    n.put("status", o.status().name());
    n.put("createdAt", o.createdAt().toString());
    return n;
  }

  /** order.detail.get 输出（1.1.0）：头 + items + address + logistics?。 */
  public ObjectNode detail(Order o) {
    ObjectNode n = listItem(o);
    ArrayNode items = n.putArray("items");
    o.items()
        .forEach(
            i -> {
              ObjectNode row = items.addObject();
              row.put("productId", i.productId());
              row.put("productName", i.productName());
              row.put("unitPrice", i.unitPriceText());
              row.put("quantity", i.quantity());
              row.put("amount", i.lineAmountText());
            });
    ObjectNode address = n.putObject("address");
    address.put("receiver", o.address().receiver());
    address.put("phoneMasked", o.address().phoneMasked());
    address.put("region", o.address().region());
    if (!o.logistics().isEmpty()) {
      LogisticsEvent first = o.logistics().get(0);
      ObjectNode lg = n.putObject("logistics");
      lg.put("carrier", first.carrier());
      lg.put("trackingNo", first.trackingNo());
      lg.put("status", o.logisticsStatus().name());
    }
    return n;
  }

  /** order.logistics.get 输出；无物流 → carrier / trackingNo 空串、NOT_SHIPPED、events []。 */
  public ObjectNode logistics(Order o) {
    ObjectNode n = mapper.createObjectNode();
    List<LogisticsEvent> ev = o.logistics();
    n.put("orderId", o.orderId());
    n.put("carrier", ev.isEmpty() ? "" : ev.get(0).carrier());
    n.put("trackingNo", ev.isEmpty() ? "" : ev.get(0).trackingNo());
    n.put("status", o.logisticsStatus().name());
    ArrayNode events = n.putArray("events");
    ev.forEach(
        e -> {
          ObjectNode row = events.addObject();
          row.put("time", e.eventTime().toString());
          row.put("location", e.location());
          row.put("description", e.description());
        });
    return n;
  }
}
