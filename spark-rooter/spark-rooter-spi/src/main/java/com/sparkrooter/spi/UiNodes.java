package com.sparkrooter.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 领域模块生成 UI Schema（ui-schema 契约）树的小工具：只负责结构（screen / component / action），不做校验——校验统一在 runtime 的
 * ScreenRegistry。四个领域的 ScreenBuilder 共用，避免各自复制一份树构造代码。
 */
public final class UiNodes {

  /** ui-schema 契约版本。 */
  public static final String SCHEMA_VERSION = "1.0";

  /** 取消按钮固定 id。 */
  public static final String CANCEL_ACTION_ID = "cancel";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private UiNodes() {}

  public static ObjectNode object() {
    return MAPPER.createObjectNode();
  }

  /** 空屏：components / actions 为空数组。 */
  public static ObjectNode screen(String screenId, String title) {
    ObjectNode s = MAPPER.createObjectNode();
    s.put("schemaVersion", SCHEMA_VERSION);
    s.put("screenId", screenId);
    s.put("title", title);
    s.putArray("components");
    s.putArray("actions");
    return s;
  }

  /** 追加一个组件，返回其 props 节点供调用方填充。 */
  public static ObjectNode component(ObjectNode screen, String id, String type) {
    ObjectNode c = ((ArrayNode) screen.get("components")).addObject();
    c.put("id", id);
    c.put("type", type);
    return c.putObject("props");
  }

  /** 提交按钮：携带后端签发的 confirmationToken。style ∈ {default, primary, danger}。 */
  public static void submitAction(
      ObjectNode screen, String id, String label, String style, String token) {
    ObjectNode a = ((ArrayNode) screen.get("actions")).addObject();
    a.put("id", id);
    a.put("type", "submit");
    a.put("label", label);
    a.put("style", style);
    a.put("confirmationToken", token);
  }

  public static void cancelAction(ObjectNode screen) {
    ObjectNode a = ((ArrayNode) screen.get("actions")).addObject();
    a.put("id", CANCEL_ACTION_ID);
    a.put("type", "cancel");
    a.put("label", "取消");
    a.put("style", "default");
  }

  /** {label, value} 项（Card.items / ResultCard.details / ConfirmationCard.items 共用形状）。 */
  public static void labelValue(ArrayNode arr, String label, String value) {
    ObjectNode o = arr.addObject();
    o.put("label", label);
    o.put("value", value);
  }

  /** 行内快捷指令（inlineAction）：intent 是自然语言，前端点击后原样作为新消息发送。 */
  public static void inlineAction(ArrayNode actions, String label, String intent) {
    ObjectNode o = actions.addObject();
    o.put("label", label);
    o.put("intent", intent);
  }

  /** Form 字段声明；options 为 null 表示非 select。 */
  public static ObjectNode formField(
      ArrayNode fields, String name, String type, String label, boolean required) {
    ObjectNode f = fields.addObject();
    f.put("name", name);
    f.put("type", type);
    f.put("label", label);
    f.put("required", required);
    return f;
  }

  /** 读取字符串字段；缺失或 null 用默认值。 */
  public static String text(JsonNode n, String field, String dflt) {
    return n != null && n.hasNonNull(field) ? n.get(field).asText() : dflt;
  }

  /**
   * 在已下发的屏（ui-schema 树）里按 component id + item label 取 Card / Result 的展示值；找不到返回空串。
   * 重校验用它比对「用户确认时看到的值」。
   */
  public static String findItemValue(JsonNode screen, String componentId, String label) {
    if (screen == null) {
      return "";
    }
    for (JsonNode c : screen.path("components")) {
      if (!componentId.equals(c.path("id").asText())) {
        continue;
      }
      JsonNode items = c.path("props").path("items");
      if (!items.isArray()) {
        items = c.path("props").path("details");
      }
      for (JsonNode it : items) {
        if (label.equals(it.path("label").asText())) {
          return it.path("value").asText("");
        }
      }
    }
    return "";
  }

  /** 契约里多数文本字段有 maxLength；超长截断而不是让整屏校验失败。 */
  public static String truncate(String s, int max) {
    if (s == null) {
      return "";
    }
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }
}
