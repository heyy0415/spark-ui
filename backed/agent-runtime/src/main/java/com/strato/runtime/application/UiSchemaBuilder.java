package com.strato.runtime.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.strato.contracts.SchemaValidator;
import com.strato.contracts.model.UiSchema;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 生成 UI Schema（Runtime → 前端）。只使用契约白名单组件；无 URL / HTML；生成后经契约校验。 确认屏必含一个 Form（agent-safety
 * §3：formData 键白名单来自 Form.props.fields[]）。
 */
@Component
public class UiSchemaBuilder {

  static final String CONFIRM_ACTION_ID = "confirm-refund";
  static final String CANCEL_ACTION_ID = "cancel";
  static final String REASON_FIELD = "reason";

  private final SchemaValidator validator;
  private final ObjectMapper mapper;

  public UiSchemaBuilder(SchemaValidator validator) {
    this.validator = validator;
    this.mapper = validator.mapper();
  }

  /** 退款确认屏：OrderCard + RefundConfirmCard(摘要) + Form{reason} + confirm/cancel。 */
  public UiSchema refundConfirmation(
      String orderId,
      JsonNode orderDetail,
      JsonNode eligibility,
      JsonNode preview,
      String confirmationToken) {
    ObjectNode orderProps = mapper.createObjectNode();
    orderProps.put("orderId", orderId);
    orderProps.put("productName", text(orderDetail, "productName", "订单 " + orderId));
    orderProps.put("amount", text(orderDetail, "amount", text(preview, "amount", "0.00")));
    orderProps.put("currency", "CNY");
    orderProps.put("status", text(orderDetail, "status", "PAID"));

    ObjectNode summary = mapper.createObjectNode();
    summary.put("orderId", orderId);
    summary.put("amount", text(preview, "amount", text(eligibility, "refundableAmount", "0.00")));
    summary.put("currency", "CNY");
    summary.put("eligible", eligibility.path("eligible").asBoolean(false));
    if (preview.hasNonNull("estimatedDays")) {
      summary.put("estimatedDays", preview.get("estimatedDays").asInt());
    }

    ObjectNode form = mapper.createObjectNode();
    ArrayNode fields = form.putArray("fields");
    ObjectNode reason = fields.addObject();
    reason.put("name", REASON_FIELD);
    reason.put("type", "select");
    reason.put("label", "退款原因");
    reason.put("required", true);
    ArrayNode options = reason.putArray("options");
    option(options, "商品破损", "DAMAGED");
    option(options, "未收到货", "NOT_RECEIVED");
    option(options, "买错了", "CHANGED_MIND");

    UiSchema ui =
        new UiSchema(
            UiSchema.VERSION,
            "refund-confirmation",
            "确认退款",
            List.of(
                new UiSchema.Component("order", UiSchema.ComponentType.OrderCard, orderProps),
                new UiSchema.Component(
                    "refund-summary", UiSchema.ComponentType.RefundConfirmCard, summary),
                new UiSchema.Component("refund-form", UiSchema.ComponentType.Form, form)),
            List.of(
                new UiSchema.Action(
                    CONFIRM_ACTION_ID,
                    UiSchema.ActionType.submit,
                    "确认退款",
                    UiSchema.ActionStyle.DANGER,
                    confirmationToken),
                new UiSchema.Action(
                    CANCEL_ACTION_ID,
                    UiSchema.ActionType.cancel,
                    "取消",
                    UiSchema.ActionStyle.DEFAULT,
                    null)));
    return validated(ui);
  }

  /** 结果屏：ResultCard。 */
  public UiSchema refundResult(JsonNode created) {
    ObjectNode props = mapper.createObjectNode();
    props.put("status", "success");
    props.put("title", "退款已提交");
    props.put("description", "退款 " + text(created, "amount", "") + " 元将在 1-3 个工作日内原路退回。");
    ArrayNode details = props.putArray("details");
    detail(details, "订单号", text(created, "orderId", ""));
    detail(details, "退款单号", text(created, "refundId", ""));
    detail(details, "退款金额", text(created, "amount", ""));
    UiSchema ui =
        new UiSchema(
            UiSchema.VERSION,
            "refund-result",
            "退款已提交",
            List.of(new UiSchema.Component("result", UiSchema.ComponentType.ResultCard, props)),
            List.of());
    return validated(ui);
  }

  /** 确认屏中 Form 声明的字段名集合，作为 formData 键白名单。 */
  public static Set<String> formKeys(UiSchema ui) {
    return ui.components().stream()
        .filter(c -> c.type() == UiSchema.ComponentType.Form)
        .flatMap(c -> streamOf(c.props().path("fields")))
        .map(f -> f.path("name").asText())
        .filter(s -> !s.isBlank())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private static java.util.stream.Stream<JsonNode> streamOf(JsonNode arr) {
    return java.util.stream.StreamSupport.stream(arr.spliterator(), false);
  }

  private UiSchema validated(UiSchema ui) {
    validator.assertValid("ui-schema", mapper.valueToTree(ui));
    return ui;
  }

  private static String text(JsonNode n, String field, String dflt) {
    return n != null && n.hasNonNull(field) ? n.get(field).asText() : dflt;
  }

  private static void option(ArrayNode arr, String label, String value) {
    ObjectNode o = arr.addObject();
    o.put("label", label);
    o.put("value", value);
  }

  private static void detail(ArrayNode arr, String label, String value) {
    ObjectNode o = arr.addObject();
    o.put("label", label);
    o.put("value", value);
  }
}
