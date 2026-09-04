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

/** refund.eligibility.check@1.2.0 */
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
    return "1.2.0";
  }

  @Override
  public JsonNode handle(JsonNode args, ExecutionContext ctx) {
    String orderId = args.get("orderId").asText();
    EligibilityPolicy.Result r = service.checkEligibility(ctx.principal().tenantId(), orderId);
    ObjectNode n = mapper.createObjectNode();
    n.put("orderId", orderId);
    n.put("eligible", r.eligible());
    n.put(
        "refundableAmount", r.refundableAmount().setScale(2, RoundingMode.HALF_UP).toPlainString());
    n.put("currency", "CNY");
    r.reason().ifPresent(reason -> n.put("reason", reason));
    return n;
  }
}
