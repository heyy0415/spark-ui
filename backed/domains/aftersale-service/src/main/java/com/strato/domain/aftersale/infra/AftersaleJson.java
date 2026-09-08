package com.strato.domain.aftersale.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.strato.domain.aftersale.domain.Aftersale;
import com.strato.spi.OrderSnapshot;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/** 售后单 / 订单摘要 → 工具输出 JSON 的唯一投影点；字段名与 tool-manifests/*.json outputSchema 一一对应。 */
@Component
public class AftersaleJson {

  private final ObjectMapper mapper;

  public AftersaleJson(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public ObjectNode item(Aftersale a) {
    ObjectNode n = mapper.createObjectNode();
    n.put("aftersaleId", a.aftersaleId());
    n.put("orderId", a.orderId());
    n.put("type", a.type().name());
    n.put("status", a.status().name());
    n.put("reason", a.reason());
    n.put("createdAt", a.createdAt().toString());
    return n;
  }

  /** aftersale.list.get 的 order 摘要（确认屏据此渲染 OrderCard，不再直读订单域）。 */
  public ObjectNode order(OrderSnapshot s) {
    ObjectNode n = mapper.createObjectNode();
    n.put("orderId", s.orderId());
    n.put("productName", s.productName());
    n.put("quantity", s.quantity());
    n.put("amount", s.amount().setScale(2, RoundingMode.HALF_UP).toPlainString());
    n.put("currency", s.currency());
    n.put("status", s.status());
    return n;
  }
}
