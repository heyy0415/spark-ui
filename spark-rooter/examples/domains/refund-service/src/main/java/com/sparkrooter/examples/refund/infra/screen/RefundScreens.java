package com.sparkrooter.examples.refund.infra.screen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.spi.ScreenBuilder;
import com.sparkrooter.spi.ScreenContext;
import com.sparkrooter.spi.UiNodes;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 退款域屏（首期 runtime UiSchemaBuilder 原样搬迁，组件收敛为官方组件映射）： 确认屏 [order: Card, refund-summary: Card,
 * refund-form: Form{reason}]，submit id confirm-refund；结果屏 [result: Result]。 只用 previousOutputs（经
 * Gateway 的干净输出）与 fixedArgs，不读领域数据。
 */
@Component
public class RefundScreens implements ScreenBuilder {

  public static final String CONFIRM_ACTION_ID = "confirm-refund";
  public static final String SUMMARY_COMPONENT_ID = "refund-summary";
  public static final String AMOUNT_LABEL = "退款金额";
  static final String REASON_FIELD = "reason";

  @Override
  public Set<String> resultToolIds() {
    return Set.of("refund.create");
  }

  @Override
  public Set<String> confirmToolIds() {
    return Set.of("refund.create");
  }

  @Override
  public JsonNode confirmation(
      String toolId,
      Map<String, String> fixedArgs,
      Map<String, JsonNode> previousOutputs,
      String token,
      ScreenContext ctx) {
    String orderId = fixedArgs.getOrDefault("orderId", "");
    JsonNode eligibility = previousOutputs.get("refund.eligibility.check");
    JsonNode preview = previousOutputs.get("refund.preview");
    if (eligibility == null || preview == null) {
      // 前置只读步骤是计划表写死的（IntentVerbs.PREREQUISITES）；缺失即编排错误，不用猜测值出屏
      throw new IllegalStateException(
          "refund confirmation requires eligibility.check and preview outputs");
    }
    String amount =
        UiNodes.text(preview, "amount", UiNodes.text(eligibility, "refundableAmount", ""));

    ObjectNode screen = UiNodes.screen("refund-confirmation", "确认退款");

    // 订单摘要全部来自 eligibility.check 的真实输出（1.3.0 起携带订单快照）；没有的字段不显示，绝不填业务默认值
    ObjectNode order = UiNodes.component(screen, "order", "Card");
    order.put("title", "订单 " + orderId);
    ArrayNode oi = order.putArray("items");
    if (eligibility.hasNonNull("productName")) {
      UiNodes.labelValue(oi, "商品", eligibility.get("productName").asText());
    }
    if (eligibility.hasNonNull("quantity")) {
      UiNodes.labelValue(oi, "件数", eligibility.get("quantity").asText());
    }
    if (eligibility.hasNonNull("orderAmount")) {
      UiNodes.labelValue(oi, "金额", eligibility.get("orderAmount").asText() + " CNY");
    }
    if (eligibility.hasNonNull("orderStatus")) {
      UiNodes.labelValue(oi, "状态", eligibility.get("orderStatus").asText());
    }

    ObjectNode summary = UiNodes.component(screen, SUMMARY_COMPONENT_ID, "Card");
    summary.put("title", "退款信息");
    ArrayNode si = summary.putArray("items");
    UiNodes.labelValue(si, AMOUNT_LABEL, amount);
    UiNodes.labelValue(si, "币种", "CNY");
    boolean eligible = eligibility != null && eligibility.path("eligible").asBoolean(false);
    ObjectNode elig = si.addObject();
    elig.put("label", "退款资格");
    elig.put("value", eligible ? "满足" : "不满足");
    elig.put("tone", eligible ? "success" : "danger");
    if (preview != null && preview.hasNonNull("estimatedDays")) {
      UiNodes.labelValue(si, "预计到账", preview.get("estimatedDays").asInt() + " 天");
    }

    ObjectNode form = UiNodes.component(screen, "refund-form", "Form");
    ArrayNode fields = form.putArray("fields");
    ObjectNode reason = UiNodes.formField(fields, REASON_FIELD, "select", "退款原因", true);
    ArrayNode options = reason.putArray("options");
    option(options, "商品破损", "DAMAGED");
    option(options, "未收到货", "NOT_RECEIVED");
    option(options, "买错了", "CHANGED_MIND");

    UiNodes.submitAction(screen, CONFIRM_ACTION_ID, "确认退款", "danger", token);
    UiNodes.cancelAction(screen);
    return screen;
  }

  @Override
  public JsonNode result(String toolId, JsonNode created, ScreenContext ctx) {
    ObjectNode screen = UiNodes.screen("refund-result", "退款已提交");
    ObjectNode props = UiNodes.component(screen, "result", "Result");
    props.put("status", "success");
    props.put("title", "退款已提交");
    props.put("description", "退款 " + UiNodes.text(created, "amount", "") + " 元将在 1-3 个工作日内原路退回。");
    ArrayNode details = props.putArray("details");
    UiNodes.labelValue(details, "订单号", UiNodes.text(created, "orderId", ""));
    UiNodes.labelValue(details, "退款单号", UiNodes.text(created, "refundId", ""));
    UiNodes.labelValue(details, AMOUNT_LABEL, UiNodes.text(created, "amount", ""));
    return screen;
  }

  /** 自检样例：与 refund.eligibility.check 1.3.0 / refund.preview 1.3.0 的 outputSchema 一致。 */
  @Override
  public Map<String, JsonNode> probeOutputs(String toolId) {
    ObjectNode elig = UiNodes.object();
    elig.put("orderId", "10003");
    elig.put("eligible", true);
    elig.put("refundableAmount", "1.00");
    elig.put("currency", "CNY");
    elig.put("orderStatus", "PAID");
    elig.put("productName", "自检专用商品");
    elig.put("quantity", 1);
    elig.put("orderAmount", "1.00");
    ObjectNode preview = UiNodes.object();
    preview.put("orderId", "10003");
    preview.put("amount", "1.00");
    preview.put("currency", "CNY");
    preview.put("estimatedDays", 3);
    return Map.of("refund.eligibility.check", elig, "refund.preview", preview);
  }

  @Override
  public String summary(String toolId, JsonNode out) {
    return switch (toolId) {
      case "refund.eligibility.check" ->
          out.path("eligible").asBoolean(false) ? "订单满足退款条件" : "订单不满足退款条件";
      case "refund.preview" ->
          "可退 "
              + out.path("amount").asText("")
              + " 元，预计 "
              + out.path("estimatedDays").asText("")
              + " 天到账";
      case "refund.create" -> "退款单 " + out.path("refundId").asText("") + " 已提交";
      default -> null;
    };
  }

  private static void option(ArrayNode arr, String label, String value) {
    ObjectNode o = arr.addObject();
    o.put("label", label);
    o.put("value", value);
  }
}
