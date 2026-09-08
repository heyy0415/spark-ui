package com.strato.domain.aftersale.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.strato.domain.aftersale.application.AftersaleService;
import com.strato.domain.aftersale.domain.Aftersale;
import com.strato.spi.ExecutionContext;
import com.strato.spi.ToolHandler;
import org.springframework.stereotype.Component;

/** aftersale.create@1.0.0：有副作用；幂等键由 Gateway 透传。 */
@Component
public class AftersaleCreateHandler implements ToolHandler {

  private final AftersaleService service;
  private final AftersaleJson json;

  public AftersaleCreateHandler(AftersaleService service, AftersaleJson json) {
    this.service = service;
    this.json = json;
  }

  @Override
  public String toolId() {
    return "aftersale.create";
  }

  @Override
  public String version() {
    return "1.0.0";
  }

  @Override
  public JsonNode handle(JsonNode args, ExecutionContext ctx) {
    Aftersale a =
        service.create(
            ctx.principal().tenantId(),
            args.get("orderId").asText(),
            Aftersale.Type.valueOf(args.get("type").asText()),
            args.get("reason").asText(),
            ctx.idempotencyKey());
    return json.item(a);
  }
}
