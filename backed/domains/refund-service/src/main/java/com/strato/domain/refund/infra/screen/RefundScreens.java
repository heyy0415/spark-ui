package com.strato.domain.refund.infra.screen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.strato.spi.ScreenBuilder;
import com.strato.spi.ScreenContext;
import com.strato.spi.UiNodes;
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
    JsonNode detail = previousOutputs.get("order.detail.get");
    JsonNode eligibility = previousOutputs.get("refund.eligibility.check");
    JsonNode preview = previousOutputs.get("refund.preview");
    String amount =
        UiNodes.text(preview, "amount", UiNodes.text(eligibility, "refundableAmount", "0.00"));

    ObjectNode screen = UiNodes.screen("refund-confirmation", "确认退款");

    ObjectNode order = UiNodes.component(screen, "order", "Card");
    order.put("title", "订单 " + orderId);
    ArrayNode oi = order.putArray("items");
    UiNodes.labelValue(oi, "商品", UiNodes.text(detail, "productName", "订单 " + orderId));
    UiNodes.labelValue(oi, "金额", UiNodes.text(detail, "amount", amount) + " CNY");
    UiNodes.labelValue(oi, "状态", UiNodes.text(detail, "status", "PAID"));

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
