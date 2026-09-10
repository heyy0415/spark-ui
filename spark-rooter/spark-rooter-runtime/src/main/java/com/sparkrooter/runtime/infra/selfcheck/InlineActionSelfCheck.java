package com.sparkrooter.runtime.infra.selfcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.model.ToolInvoke;
import com.sparkrooter.contracts.model.UiSchema;
import com.sparkrooter.runtime.application.port.ToolGatewayClient;
import com.sparkrooter.runtime.application.screen.ScreenRegistry;
import com.sparkrooter.spi.ScreenContext;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 行内指令自检（spec S9 / contracts.md §4）：经 Gateway 拉全部订单与商品，生成列表屏，断言每个 Table.rows[].actions[]：intent 含该行
 * id，且 label ↔ 动词映射一致。防止屏层生成的按钮文本与规划器动词表脱节（点了按钮却路由不到目标工具）。
 */
public class InlineActionSelfCheck implements com.sparkrooter.spi.SelfCheck {

  private static final Logger log = LoggerFactory.getLogger(InlineActionSelfCheck.class);

  /** label → intent 必须包含的动词（与 contracts.md §4 表一致）。 */
  static final Map<String, String> LABEL_VERB =
      Map.of(
          "查看物流", "物流",
          "申请售后", "售后",
          "删除订单", "删除",
          "退款", "退款",
          "查看商品", "查看商品",
          "返回列表", "商品");

  private final ToolGatewayClient gateway;
  private final ScreenRegistry screens;
  private final ObjectMapper mapper;

  public InlineActionSelfCheck(
      ToolGatewayClient gateway, ScreenRegistry screens, ObjectMapper mapper) {
    this.gateway = gateway;
    this.screens = screens;
    this.mapper = mapper;
  }

  @Override
  public String name() {
    return "inline actions";
  }

  @Override
  public void run() {
    int checked = 0;
    checked += verify("order.list.search", "1.1.0", "orders");
    checked += verify("product.list.search", "1.0.0", "products");
    checked += verifyCard("product.detail.get", "1.0.0", "P-1003", "product", "有什么商品");
    checked += verifyCard("order.detail.get", "1.1.0", "10002", "order", "查看订单 10002 的物流");
    log.info("selfcheck: inline actions OK ({} actions)", checked);
  }

  /** Card.actions（change 5）：详情卡底部行内指令 label 在绑定表内、intent 为期望文案。 */
  private int verifyCard(
      String toolId, String version, String id, String componentId, String expectIntent) {
    ObjectNode args = mapper.createObjectNode();
    args.put(toolId.startsWith("product") ? "productId" : "orderId", id);
    ToolInvoke.Response resp =
        gateway.invoke(
            new ToolInvoke.Request(
                toolId,
                version,
                args,
                new ToolInvoke.ExecutionContext(
                    "run_selfcheck",
                    "tc_card_" + componentId,
                    "selfcheck",
                    "selfcheck-card-" + componentId,
                    null)));
    if (resp.output() == null) {
      throw new IllegalStateException(toolId + " returned no output for selfcheck");
    }
    UiSchema ui = screens.result(toolId, resp.output(), new ScreenContext("run_selfcheck"));
    for (UiSchema.Component c : ui.components()) {
      if (!componentId.equals(c.id())) {
        continue;
      }
      JsonNode actions = c.props().path("actions");
      if (!actions.isArray() || actions.isEmpty()) {
        throw new IllegalStateException(
            "Card " + componentId + " of " + toolId + " has no actions");
      }
      String label = actions.get(0).path("label").asText();
      String intent = actions.get(0).path("intent").asText();
      if (!LABEL_VERB.containsKey(label) || !intent.equals(expectIntent)) {
        throw new IllegalStateException(
            "Card action mismatch " + toolId + ": " + label + " → " + intent);
      }
      return actions.size();
    }
    throw new IllegalStateException("component " + componentId + " not in " + toolId + " screen");
  }

  private int verify(String toolId, String version, String componentId) {
    ObjectNode args = mapper.createObjectNode();
    args.put("limit", 50);
    ToolInvoke.Response resp =
        gateway.invoke(
            new ToolInvoke.Request(
                toolId,
                version,
                args,
                new ToolInvoke.ExecutionContext(
                    "run_selfcheck",
                    "tc_inline_" + componentId,
                    "selfcheck",
                    "selfcheck-inline-" + componentId,
                    null)));
    if (resp.output() == null) {
      throw new IllegalStateException(toolId + " returned no output for selfcheck");
    }
    UiSchema ui = screens.result(toolId, resp.output(), new ScreenContext("run_selfcheck"));
    int count = 0;
    for (UiSchema.Component c : ui.components()) {
      if (!componentId.equals(c.id())) {
        continue;
      }
      for (JsonNode row : c.props().path("rows")) {
        String id = row.path("id").asText();
        for (JsonNode a : row.path("actions")) {
          String label = a.path("label").asText();
          String intent = a.path("intent").asText();
          String verb = LABEL_VERB.get(label);
          if (verb == null) {
            throw new IllegalStateException("unknown inline action label: " + label);
          }
          if (!intent.contains(id) || !intent.contains(verb)) {
            throw new IllegalStateException("inline action mismatch row=" + id + " label=" + label);
          }
          count++;
        }
      }
    }
    if (count == 0) {
      throw new IllegalStateException("no inline actions generated for " + toolId);
    }
    return count;
  }
}
