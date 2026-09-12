package com.sparkrooter.runtime.infra.selfcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.model.ToolInvoke;
import com.sparkrooter.contracts.model.UiSchema;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.runtime.application.port.ToolGatewayClient;
import com.sparkrooter.runtime.application.screen.ScreenRegistry;
import com.sparkrooter.spi.ScreenContext;
import com.sparkrooter.spi.tool.ToolMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 行内指令自检（contracts.md §4）：对每个声明了 clarifiesEntity 的列表工具，经 Gateway 拉列表、生成结果屏，断言每个
 * Table.rows[].actions[] 的 label 非空且 intent 含该行 id（下一轮模型据此抽到实体）。不含任何写死的 toolId / 领域词。
 */
public class InlineActionSelfCheck implements com.sparkrooter.spi.SelfCheck {

  private static final Logger log = LoggerFactory.getLogger(InlineActionSelfCheck.class);

  /** 行内指令的通用约束：label 非空、intent 含该行实体 id（contracts.md §4）；label ↔ 动词的领域语义由模型理解，内核不校。 */
  private final ToolGatewayClient gateway;

  private final ScreenRegistry screens;
  private final ObjectMapper mapper;
  private final ToolMetaRegistry meta;

  public InlineActionSelfCheck(
      ToolGatewayClient gateway,
      ScreenRegistry screens,
      ObjectMapper mapper,
      ToolMetaRegistry meta) {
    this.gateway = gateway;
    this.screens = screens;
    this.mapper = mapper;
    this.meta = meta;
  }

  @Override
  public String name() {
    return "inline actions";
  }

  @Override
  public void run() {
    int checked = 0;
    for (ToolMeta m : meta.all()) {
      if (m.clarifies()) {
        checked += verify(m.toolId(), m.version());
      }
    }
    log.info("selfcheck: inline actions OK ({} actions)", checked);
  }

  private int verify(String toolId, String version) {
    ObjectNode args = mapper.createObjectNode();
    ToolInvoke.Response resp =
        gateway.invoke(
            new ToolInvoke.Request(
                toolId,
                version,
                args,
                new ToolInvoke.ExecutionContext(
                    "run_selfcheck",
                    "tc_inline_" + toolId,
                    "selfcheck",
                    "selfcheck-inline-" + toolId,
                    null)));
    if (resp.output() == null) {
      throw new IllegalStateException(toolId + " returned no output for selfcheck");
    }
    UiSchema ui = screens.result(toolId, resp.output(), new ScreenContext("run_selfcheck"));
    int count = 0;
    for (UiSchema.Component c : ui.components()) {
      if (c.type() != UiSchema.ComponentType.Table) {
        continue;
      }
      for (JsonNode row : c.props().path("rows")) {
        String id = row.path("id").asText();
        for (JsonNode a : row.path("actions")) {
          String label = a.path("label").asText();
          String intent = a.path("intent").asText();
          if (label.isBlank() || !intent.contains(id)) {
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
