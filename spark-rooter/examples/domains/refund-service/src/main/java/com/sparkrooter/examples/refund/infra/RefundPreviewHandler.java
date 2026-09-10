package com.sparkrooter.examples.refund.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.examples.refund.application.RefundService;
import com.sparkrooter.spi.ExecutionContext;
import com.sparkrooter.spi.ToolHandler;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/** refund.preview@1.3.0 */
@Component
public class RefundPreviewHandler implements ToolHandler {

  private final RefundService service;
  private final ObjectMapper mapper;

  public RefundPreviewHandler(RefundService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @Override
  public String toolId() {
    return "refund.preview";
  }

  @Override
  public String version() {
    return "1.3.0";
  }

  @Override
  public JsonNode handle(JsonNode args, ExecutionContext ctx) {
    RefundService.Preview p =
        service.preview(ctx.principal().tenantId(), args.get("orderId").asText());
    ObjectNode n = mapper.createObjectNode();
    n.put("orderId", p.orderId());
    n.put("amount", p.amount().setScale(2, RoundingMode.HALF_UP).toPlainString());
    n.put("currency", "CNY");
    n.put("estimatedDays", p.estimatedDays());
    return n;
  }
}
