package com.sparkrooter.examples.refund.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.examples.refund.application.RefundService;
import com.sparkrooter.spi.ExecutionContext;
import com.sparkrooter.spi.ToolHandler;
import org.springframework.stereotype.Component;

/** refund.status.get@1.0.0 */
@Component
public class RefundStatusGetHandler implements ToolHandler {

  private final RefundService service;
  private final ObjectMapper mapper;

  public RefundStatusGetHandler(RefundService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @Override
  public String toolId() {
    return "refund.status.get";
  }

  @Override
  public String version() {
    return "1.0.0";
  }

  @Override
  public JsonNode handle(JsonNode args, ExecutionContext ctx) {
    String orderId = args.get("orderId").asText();
    ArrayNode arr = mapper.createArrayNode();
    service
        .status(ctx.principal().tenantId(), orderId)
        .forEach(
            r -> {
              ObjectNode n = arr.addObject();
              n.put("refundId", r.refundId());
              n.put("amount", r.amountText());
              n.put("status", r.status().name());
              n.put("createdAt", r.createdAt().toString());
            });
    ObjectNode out = mapper.createObjectNode();
    out.put("orderId", orderId);
    out.set("refunds", arr);
    return out;
  }
}
