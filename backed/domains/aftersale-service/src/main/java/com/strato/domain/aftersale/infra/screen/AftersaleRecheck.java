package com.strato.domain.aftersale.infra.screen;

import com.fasterxml.jackson.databind.JsonNode;
import com.strato.domain.aftersale.domain.Aftersale;
import com.strato.domain.aftersale.domain.AftersalePolicy;
import com.strato.spi.ConfirmationRecheck;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * aftersale.create 确认后重校验：经 Gateway 重调 aftersale.list.get（含 order 摘要），AftersalePolicy 判定订单状态与进行中售后。
 */
@Component
public class AftersaleRecheck implements ConfirmationRecheck {

  @Override
  public String toolId() {
    return "aftersale.create";
  }

  @Override
  public String recheckToolId() {
    return "aftersale.list.get";
  }

  @Override
  public Map<String, String> recheckArgs(Map<String, String> fixedArgs) {
    return Map.of("orderId", fixedArgs.getOrDefault("orderId", ""));
  }

  @Override
  public Optional<String> reject(JsonNode recheckOutput, JsonNode shownUi) {
    String status = recheckOutput.path("order").path("status").asText("");
    List<Aftersale> existing = new ArrayList<>();
    for (JsonNode it : recheckOutput.path("items")) {
      // 只需要 status 参与策略判定；其余字段按输出原样填充
      existing.add(
          new Aftersale(
              it.path("aftersaleId").asText(""),
              it.path("orderId").asText(""),
              "",
              Aftersale.Type.valueOf(it.path("type").asText("RETURN")),
              Aftersale.Status.valueOf(it.path("status").asText("CANCELLED")),
              it.path("reason").asText(""),
              Instant.EPOCH));
    }
    return AftersalePolicy.reject(status, existing);
  }

  @Override
  public Map<String, String> trustedArgs(JsonNode recheckOutput) {
    return Map.of();
  }
}
