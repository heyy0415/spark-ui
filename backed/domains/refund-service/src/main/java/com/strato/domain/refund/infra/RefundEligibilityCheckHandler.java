package com.strato.domain.refund.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.strato.domain.refund.application.RefundService;
import com.strato.domain.refund.domain.EligibilityPolicy;
import com.strato.spi.ExecutionContext;
import com.strato.spi.ToolHandler;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/** refund.eligibility.check@1.3.0：资格判定 + 订单摘要（确认屏渲染订单 Card 用）。 */
@Component
public class RefundEligibilityCheckHandler implements ToolHandler {

  private final RefundService service;
  private final ObjectMapper mapper;

  public RefundEligibilityCheckHandler(RefundService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @Override
  public String toolId() {
    return "refund.eligibility.check";
  }

  @Override
  public String version() {
    return "1.3.0";
  }

  @Override
  public JsonNode handle(JsonNode args, ExecutionContext ctx) {
    String orderId = args.get("orderId").asText();
    RefundService.Eligibility e = service.eligibility(ctx.principal().tenantId(), orderId);
    EligibilityPolicy.Result r = e.result();
    ObjectNode n = mapper.createObjectNode();
    n.put("orderId", orderId);
    n.put("eligible", r.eligible());
    // 订单摘要（真实状态 / 商品 / 金额 / 件数）：确认屏据此渲染订单 Card
    n.put("orderStatus", e.order().status());
    n.put("productName", e.order().productName());
    n.put("quantity", e.order().quantity());
    n.put("orderAmount", e.order().amount().setScale(2, RoundingMode.HALF_UP).toPlainString());
    n.put(
        "refundableAmount", r.refundableAmount().setScale(2, RoundingMode.HALF_UP).toPlainString());
    n.put("currency", "CNY");
    r.reason().ifPresent(reason -> n.put("reason", reason));
    return n;
  }
}
