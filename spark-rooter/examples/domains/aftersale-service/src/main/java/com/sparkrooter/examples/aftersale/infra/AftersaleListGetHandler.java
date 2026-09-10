package com.sparkrooter.examples.aftersale.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.examples.aftersale.application.AftersaleService;
import com.sparkrooter.spi.ExecutionContext;
import com.sparkrooter.spi.ToolHandler;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** aftersale.list.get@1.0.0：带 orderId 时另返回 order 摘要（供售后确认屏渲染 OrderCard）。 */
@Component
public class AftersaleListGetHandler implements ToolHandler {

  private final AftersaleService service;
  private final AftersaleJson json;
  private final ObjectMapper mapper;

  public AftersaleListGetHandler(
      AftersaleService service, AftersaleJson json, ObjectMapper mapper) {
    this.service = service;
    this.json = json;
    this.mapper = mapper;
  }

  @Override
  public String toolId() {
    return "aftersale.list.get";
  }

  @Override
  public String version() {
    return "1.0.0";
  }

  @Override
  public JsonNode handle(JsonNode args, ExecutionContext ctx) {
    String tenantId = ctx.principal().tenantId();
    Optional<String> orderId =
        args.hasNonNull("orderId") ? Optional.of(args.get("orderId").asText()) : Optional.empty();
    ObjectNode out = mapper.createObjectNode();
    ArrayNode items = out.putArray("items");
    service.list(tenantId, orderId).forEach(a -> items.add(json.item(a)));
    orderId.ifPresent(id -> out.set("order", json.order(service.order(tenantId, id))));
    return out;
  }
}
