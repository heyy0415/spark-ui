package com.strato.domain.refund.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.strato.domain.refund.application.RefundService;
import com.strato.domain.refund.domain.Refund;
import com.strato.spi.ExecutionContext;
import com.strato.spi.ToolHandler;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/** refund.create@2.1.0。高风险、有副作用；幂等键来自 ExecutionContext。 */
@Component
public class RefundCreateHandler implements ToolHandler {

  private final RefundService service;
  private final ObjectMapper mapper;

  public RefundCreateHandler(RefundService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @Override
  public String toolId() {
    return "refund.create";
  }

  @Override
  public String version() {
    return "2.1.0";
  }

  @Override
  public JsonNode handle(JsonNode args, ExecutionContext ctx) {
    Refund r =
        service.create(
            ctx.principal().tenantId(),
            args.get("orderId").asText(),
            new BigDecimal(args.get("amount").asText()),
            args.get("reason").asText(),
            ctx.idempotencyKey());
    return toJson(r);
  }

  ObjectNode toJson(Refund r) {
    ObjectNode n = mapper.createObjectNode();
    n.put("refundId", r.refundId());
    n.put("orderId", r.orderId());
    n.put("amount", r.amountText());
    n.put("currency", r.currency());
    n.put("status", r.status().name());
    n.put("createdAt", r.createdAt().toString());
    return n;
  }
}
