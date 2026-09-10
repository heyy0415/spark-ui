package com.sparkrooter.examples.aftersale.infra.screen;

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
 * 售后域屏：列表 [order: Card?, aftersales: Table]；确认 [order: Card, aftersale-form: Form{type,
 * reason}]（submit confirm-aftersale）；结果 [result: Result]。确认屏的订单 Card 只用前置 aftersale.list.get 输出里的
 * order 摘要。
 */
@Component
public class AftersaleScreens implements ScreenBuilder {

  public static final String CONFIRM_ACTION_ID = "confirm-aftersale";

  @Override
  public Set<String> resultToolIds() {
    return Set.of("aftersale.list.get", "aftersale.create");
  }

  @Override
  public Set<String> confirmToolIds() {
    return Set.of("aftersale.create");
  }

  @Override
  public JsonNode result(String toolId, JsonNode out, ScreenContext ctx) {
    return "aftersale.list.get".equals(toolId) ? list(out) : created(out);
  }

  @Override
  public JsonNode confirmation(
      String toolId,
      Map<String, String> fixedArgs,
      Map<String, JsonNode> previousOutputs,
      String token,
      ScreenContext ctx) {
    String orderId = fixedArgs.getOrDefault("orderId", "");
    JsonNode list = previousOutputs.get("aftersale.list.get");
    JsonNode order = list == null ? null : list.get("order");
    ObjectNode screen = UiNodes.screen("aftersale-confirmation", "申请售后");
    orderCard(screen, orderId, order);
    ObjectNode form = UiNodes.component(screen, "aftersale-form", "Form");
    ArrayNode fields = form.putArray("fields");
    ObjectNode type = UiNodes.formField(fields, "type", "select", "售后类型", true);
    ArrayNode options = type.putArray("options");
    for (String[] o : new String[][] {{"退货", "RETURN"}, {"换货", "EXCHANGE"}, {"维修", "REPAIR"}}) {
      ObjectNode op = options.addObject();
      op.put("label", o[0]);
      op.put("value", o[1]);
    }
    UiNodes.formField(fields, "reason", "text", "问题描述", true);
    UiNodes.submitAction(screen, CONFIRM_ACTION_ID, "提交售后申请", "primary", token);
    UiNodes.cancelAction(screen);
    return screen;
  }

  @Override
  public String summary(String toolId, JsonNode out) {
    return switch (toolId) {
      case "aftersale.list.get" -> "该订单有 " + out.path("items").size() + " 条售后记录";
      case "aftersale.create" -> "售后单 " + out.path("aftersaleId").asText("") + " 已提交";
      default -> null;
    };
  }

  private static void orderCard(ObjectNode screen, String orderId, JsonNode order) {
    ObjectNode card = UiNodes.component(screen, "order", "Card");
    card.put("title", "订单 " + orderId);
    ArrayNode items = card.putArray("items");
    UiNodes.labelValue(items, "商品", UiNodes.text(order, "productName", "订单 " + orderId));
    UiNodes.labelValue(items, "件数", UiNodes.text(order, "quantity", "-"));
    UiNodes.labelValue(
        items,
        "金额",
        UiNodes.text(order, "amount", "-") + " " + UiNodes.text(order, "currency", "CNY"));
    UiNodes.labelValue(items, "状态", UiNodes.text(order, "status", "-"));
  }

  private static ObjectNode list(JsonNode out) {
    ObjectNode screen = UiNodes.screen("aftersale-list", "售后记录");
    if (out.hasNonNull("order")) {
      orderCard(screen, out.get("order").path("orderId").asText(""), out.get("order"));
    }
    ObjectNode table = UiNodes.component(screen, "aftersales", "Table");
    ArrayNode columns = table.putArray("columns");
    for (String[] c :
        new String[][] {
          {"aftersaleId", "售后单号"},
          {"orderId", "订单号"},
          {"type", "类型"},
          {"status", "状态"},
          {"reason", "原因"},
          {"createdAt", "时间"}
        }) {
      ObjectNode col = columns.addObject();
      col.put("key", c[0]);
      col.put("title", c[1]);
    }
    ArrayNode rows = table.putArray("rows");
    for (JsonNode it : out.path("items")) {
      ObjectNode row = rows.addObject();
      row.put("id", it.path("aftersaleId").asText());
      ObjectNode cells = row.putObject("cells");
      for (String k : new String[] {"aftersaleId", "orderId", "type", "status", "createdAt"}) {
        cells.put(k, it.path(k).asText(""));
      }
      cells.put("reason", UiNodes.truncate(it.path("reason").asText(""), 200));
    }
    table.put("total", rows.size());
    if (rows.isEmpty()) {
      table.put("emptyText", "暂无售后记录");
    }
    return screen;
  }

  private static ObjectNode created(JsonNode out) {
    ObjectNode screen = UiNodes.screen("aftersale-result", "售后申请已提交");
    ObjectNode props = UiNodes.component(screen, "result", "Result");
    props.put("status", "success");
    props.put("title", "售后申请已提交");
    props.put("description", "我们会在 1-2 个工作日内审核，请留意通知。");
    ArrayNode details = props.putArray("details");
    UiNodes.labelValue(details, "售后单号", out.path("aftersaleId").asText(""));
    UiNodes.labelValue(details, "订单号", out.path("orderId").asText(""));
    UiNodes.labelValue(details, "类型", out.path("type").asText(""));
    UiNodes.labelValue(details, "状态", out.path("status").asText(""));
    return screen;
  }
}
