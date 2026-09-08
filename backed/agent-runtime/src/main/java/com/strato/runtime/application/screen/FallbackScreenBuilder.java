package com.strato.runtime.application.screen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.strato.spi.ScreenBuilder;
import com.strato.spi.ScreenContext;
import com.strato.spi.UiNodes;
import java.util.Map;
import java.util.Set;

/**
 * 没有领域屏时的兜底：结果 → Card 列出输出的顶层键值；确认 → Card 列出参数（无 Form：契约 Form.fields minItems 1， 空 Form 会被校验拒绝；无
 * Form 的确认屏 formData 白名单为空集）。不声明任何 toolId，只由 ScreenRegistry 兜底调用。
 */
final class FallbackScreenBuilder implements ScreenBuilder {

  private static final int MAX_ITEMS = 32;
  private static final int MAX_VALUE = 200;

  @Override
  public Set<String> resultToolIds() {
    return Set.of();
  }

  @Override
  public Set<String> confirmToolIds() {
    return Set.of();
  }

  @Override
  public JsonNode result(String toolId, JsonNode output, ScreenContext ctx) {
    ObjectNode screen = UiNodes.screen("result", "执行结果");
    ObjectNode props = UiNodes.component(screen, "result", "Card");
    props.put("title", "执行结果");
    ArrayNode items = props.putArray("items");
    int n = 0;
    for (Map.Entry<String, JsonNode> e : output.properties()) {
      if (n++ >= MAX_ITEMS) {
        break;
      }
      UiNodes.labelValue(items, UiNodes.truncate(e.getKey(), 80), render(e.getValue()));
    }
    return screen;
  }

  @Override
  public JsonNode confirmation(
      String toolId,
      Map<String, String> fixedArgs,
      Map<String, JsonNode> previousOutputs,
      String token,
      ScreenContext ctx) {
    ObjectNode screen = UiNodes.screen("confirmation", "请确认操作");
    ObjectNode props = UiNodes.component(screen, "confirm", "Card");
    props.put("title", "请确认操作");
    props.put("description", "即将执行：" + toolId);
    ArrayNode items = props.putArray("items");
    fixedArgs.forEach((k, v) -> UiNodes.labelValue(items, k, UiNodes.truncate(v, MAX_VALUE)));
    // action id 只允许 [a-z0-9-]
    UiNodes.submitAction(screen, "confirm-" + toolId.replace('.', '-'), "确认", "danger", token);
    UiNodes.cancelAction(screen);
    return screen;
  }

  private static String render(JsonNode v) {
    if (v.isValueNode()) {
      return UiNodes.truncate(v.asText(), MAX_VALUE);
    }
    return v.isArray() ? "（" + v.size() + " 项）" : UiNodes.truncate(v.toString(), MAX_VALUE);
  }
}
