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
import org.springframework.stereotype.Component;

/**
 * 行内指令自检（spec S9 / contracts.md §4）：经 Gateway 拉全部订单与商品，生成列表屏，断言每个 Table.rows[].actions[]：intent 含该行
 * id，且 label ↔ 动词映射一致。防止屏层生成的按钮文本与规划器动词表脱节（点了按钮却路由不到目标工具）。
 */
@Component
public class InlineActionSelfCheck implements com.sparkrooter.spi.SelfCheck {

  private static final Logger log = LoggerFactory.getLogger(InlineActionSelfCheck.class);

  /** label → intent 必须包含的动词（与 contracts.md §4 表一致）。 */
  static final Map<String, String> LABEL_VERB =
      Map.of("查看物流", "物流", "申请售后", "售后", "删除订单", "删除", "退款", "退款", "查看商品", "查看商品");

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
    log.info("selfcheck: inline actions OK ({} actions)", checked);
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
                    "user_001",
                    "tenant_001",
                    "selfcheck-inline-" + componentId,
                    null)));
    if (resp.output() == null) {
      throw new IllegalStateException(toolId + " returned no output for selfcheck");
    }
    UiSchema ui =
        screens.result(
            toolId, resp.output(), new ScreenContext("run_selfcheck", "user_001", "tenant_001"));
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
