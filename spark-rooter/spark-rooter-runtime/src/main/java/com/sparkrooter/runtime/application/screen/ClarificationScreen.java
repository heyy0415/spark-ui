package com.sparkrooter.runtime.application.screen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.spi.UiNodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 澄清屏（spec §2.6）：用户消息缺某类实体时，Runtime 调 @SparkTool(clarifiesEntity=该类型) 的列表工具拿原始输出，在这里投影为通用 Table，
 * 不经领域 ScreenBuilder：每项以「输出里名为 &lt;实体类型&gt;Id 的字段」为行 id，其余标量字段为列（最多 6 列），每行一个行内指令 {label: 原动词标签,
 * intent: "&lt;原消息&gt; &lt;实体中文名&gt; &lt;id&gt;"}。不含领域词汇，实体中文名由调用方传入。
 */
public final class ClarificationScreen {

  /** 契约 Table.cells 最多 16 键；这里只取前几个标量列，保证屏可读。 */
  static final int MAX_COLUMNS = 6;

  public static final String TEXT = "请选择要操作的对象";

  private ClarificationScreen() {}

  /**
   * @param entityType 缺失的实体类型（小写，如 order）
   * @param entityLabel 该实体的中文名（如「订单」），拼进 intent 让下一轮能抽到 ID
   * @param verbLabel 用户原动词的按钮文案（如「申请售后」）
   * @param originalMessage 用户原话（原样拼回 intent）
   * @param listOutput 澄清列表工具的原始输出（含 items[]）
   * @return 屏树；items 为空返回 empty
   */
  public static Optional<ObjectNode> build(
      String entityType,
      String entityLabel,
      String verbLabel,
      String originalMessage,
      JsonNode listOutput) {
    JsonNode items = listOutput == null ? null : listOutput.path("items");
    if (items == null || !items.isArray() || items.isEmpty()) {
      return Optional.empty();
    }
    String idField = entityType + "Id";
    List<String> columns = new ArrayList<>();
    for (JsonNode first : items) {
      first
          .fieldNames()
          .forEachRemaining(
              f -> {
                if (columns.size() < MAX_COLUMNS && first.get(f).isValueNode()) {
                  columns.add(f);
                }
              });
      break;
    }
    if (!columns.contains(idField)) {
      return Optional.empty();
    }
    ObjectNode screen = UiNodes.screen("clarify-" + entityType, TEXT);
    ObjectNode table = UiNodes.component(screen, "clarify", "Table");
    ArrayNode cols = table.putArray("columns");
    for (String c : columns) {
      ObjectNode col = cols.addObject();
      col.put("key", c);
      col.put("title", c);
    }
    ArrayNode rows = table.putArray("rows");
    int n = 0;
    for (JsonNode it : items) {
      String id = it.path(idField).asText("");
      if (id.isBlank()) {
        continue;
      }
      ObjectNode row = rows.addObject();
      row.put("id", id);
      ObjectNode cells = row.putObject("cells");
      for (String c : columns) {
        cells.put(c, UiNodes.truncate(it.path(c).asText(""), 200));
      }
      UiNodes.inlineAction(
          row.putArray("actions"),
          UiNodes.truncate(verbLabel, 32),
          UiNodes.truncate(originalMessage + " " + entityLabel + " " + id, 200));
      if (++n >= 50) {
        break;
      }
    }
    table.put("total", items.size());
    return Optional.of(screen);
  }

  /** 实体类型 → 中文名（拼进 intent 供下一轮正则抽取）；与 ArgumentExtractor 的实体正则前缀同源。 */
  public static String label(String entityType) {
    return switch (entityType) {
      case "order" -> "订单";
      case "product" -> "商品";
      default -> entityType;
    };
  }

  /** 供 ToolMetaRegistry 查澄清工具后的日志。 */
  public static String describe(ToolMetaRegistry.ToolMeta m) {
    return m.toolId() + "@" + m.version();
  }
}
