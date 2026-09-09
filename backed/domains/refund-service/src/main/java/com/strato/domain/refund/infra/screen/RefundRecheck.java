package com.strato.domain.refund.infra.screen;

import com.fasterxml.jackson.databind.JsonNode;
import com.strato.spi.ConfirmationRecheck;
import com.strato.spi.UiNodes;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * refund.create 确认后重校验（首期 runtime M3 逻辑原样迁出）：经 Gateway 重调 refund.eligibility.check； 不 eligible
 * 或可退金额 != 确认屏 refund-summary Card 上展示的「退款金额」→ 拒绝；金额只信重校验结果（trustedArgs 覆盖 formData）。
 */
@Component
public class RefundRecheck implements ConfirmationRecheck {

  @Override
  public String toolId() {
    return "refund.create";
  }

  @Override
  public String recheckToolId() {
    return "refund.eligibility.check";
  }

  @Override
  public Map<String, String> recheckArgs(Map<String, String> fixedArgs) {
    return Map.of("orderId", fixedArgs.getOrDefault("orderId", ""));
  }

  @Override
  public Optional<String> reject(JsonNode recheckOutput, JsonNode shownUi) {
    if (!recheckOutput.path("eligible").asBoolean(false)) {
      return Optional.of("order no longer eligible");
    }
    String trusted = recheckOutput.path("refundableAmount").asText("");
    String shown =
        UiNodes.findItemValue(
            shownUi, RefundScreens.SUMMARY_COMPONENT_ID, RefundScreens.AMOUNT_LABEL);
    if (trusted.isEmpty() || !trusted.equals(shown)) {
      return Optional.of("refundable amount changed since confirmation");
    }
    return Optional.empty();
  }

  @Override
  public java.util.Set<String> trustedArgKeys() {
    return java.util.Set.of("amount");
  }

  @Override
  public Map<String, String> trustedArgs(JsonNode recheckOutput) {
    String amount = recheckOutput.path("refundableAmount").asText("");
    return amount.isEmpty() ? Map.of() : Map.of("amount", amount);
  }
}
