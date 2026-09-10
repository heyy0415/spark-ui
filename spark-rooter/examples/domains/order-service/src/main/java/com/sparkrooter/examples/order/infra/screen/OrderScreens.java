package com.sparkrooter.examples.order.infra.screen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.spi.ScreenBuilder;
import com.sparkrooter.spi.ScreenContext;
import com.sparkrooter.spi.UiNodes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 订单域屏（spec §2.4.1 屏映射表；component id 写死，e2e 与重校验按 id 取值）： 列表 [orders: Table]（行内按钮按状态）；详情 [order:
 * Card, logistics: Card, logistics-events: Timeline]；物流 [logistics: Card, logistics-events:
 * Timeline]； 删除确认 [order: Card]（末项 danger，无 Form，submit confirm-delete）；删除结果 [result:
 * Result]。只用工具输出，不读领域数据。
 */
@Component
public class OrderScreens implements ScreenBuilder {

  public static final String CONFIRM_ACTION_ID = "confirm-delete";
  static final String DELETE_WARNING = "删除后订单将从列表消失，不可恢复";
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

  @Override
  public Set<String> resultToolIds() {
    return Set.of("order.list.search", "order.detail.get", "order.logistics.get", "order.delete");
  }

  @Override
  public Set<String> confirmToolIds() {
    return Set.of("order.delete");
  }

  @Override
  public JsonNode result(String toolId, JsonNode out, ScreenContext ctx) {
    return switch (toolId) {
      case "order.list.search" -> list(out);
      case "order.detail.get" -> detail(out);
      case "order.logistics.get" -> logistics(out);
      case "order.delete" -> deleted(out);
      default -> throw new IllegalArgumentException("OrderScreens does not build " + toolId);
    };
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
    ObjectNode screen = UiNodes.screen("delete-confirmation", "确认删除订单");
    ObjectNode card = UiNodes.component(screen, "order", "Card");
    card.put("title", "订单 " + orderId);
    ArrayNode items = card.putArray("items");
    UiNodes.labelValue(items, "商品", UiNodes.text(detail, "productName", "订单 " + orderId));
    UiNodes.labelValue(items, "件数", UiNodes.text(detail, "quantity", "-"));
    UiNodes.labelValue(items, "金额", money(detail));
    UiNodes.labelValue(items, "状态", UiNodes.text(detail, "status", "-"));
    ObjectNode warn = items.addObject();
    warn.put("label", "提示");
    warn.put("value", DELETE_WARNING);
    warn.put("tone", "danger");
    UiNodes.submitAction(screen, CONFIRM_ACTION_ID, "确认删除", "danger", token);
    UiNodes.cancelAction(screen);
    return screen;
  }

  @Override
  public String summary(String toolId, JsonNode out) {
    return switch (toolId) {
      case "order.list.search" -> "找到 " + out.path("total").asInt() + " 个订单";
      case "order.detail.get" ->
          "订单 " + out.path("orderId").asText("") + " " + out.path("status").asText("");
      case "order.logistics.get" -> "物流状态 " + out.path("status").asText("");
      case "order.delete" -> "订单 " + out.path("orderId").asText("") + " 已删除";
      default -> null;
    };
  }

  // ------------------------------------------------------------------ 各屏

  private static ObjectNode list(JsonNode out) {
    ObjectNode screen = UiNodes.screen("order-list", "我的订单");
    ObjectNode table = UiNodes.component(screen, "orders", "Table");
    ArrayNode columns = table.putArray("columns");
    for (String[] c :
        new String[][] {
          {"orderId", "订单号"},
          {"productName", "商品"},
          {"quantity", "件数"},
          {"amount", "金额"},
          {"status", "状态"},
          {"createdAt", "下单时间"}
        }) {
      ObjectNode col = columns.addObject();
      col.put("key", c[0]);
      col.put("title", c[1]);
    }
    ArrayNode rows = table.putArray("rows");
    for (JsonNode it : out.path("items")) {
      String id = it.path("orderId").asText();
      ObjectNode row = rows.addObject();
      row.put("id", id);
      ObjectNode cells = row.putObject("cells");
      cells.put("orderId", id);
      cells.put("productName", it.path("productName").asText(""));
      cells.put("quantity", it.path("quantity").asText(""));
      cells.put("amount", money(it));
      cells.put("status", it.path("status").asText(""));
      cells.put("createdAt", time(it.path("createdAt").asText("")));
      ArrayNode actions = row.putArray("actions");
      for (Action a : actionsFor(it.path("status").asText(""), id)) {
        UiNodes.inlineAction(actions, a.label(), a.intent());
      }
    }
    int total = out.path("total").asInt();
    table.put("total", total);
    if (rows.isEmpty()) {
      table.put("emptyText", "暂无订单");
    } else if (total > rows.size()) {
      table.put("emptyText", "共 " + total + " 单，仅展示最近 " + rows.size() + " 单");
    }
    return screen;
  }

  /** 一个行内自然语言指令：label ↔ 动词映射与 contracts.md §4 一致，InlineActionSelfCheck 校验。 */
  public record Action(String label, String intent) {}

  /** 行内指令按订单状态（spec §2.3）。 */
  public static List<Action> actionsFor(String status, String orderId) {
    Action logistics = new Action("查看物流", "查看订单 " + orderId + " 的物流");
    Action aftersale = new Action("申请售后", "订单 " + orderId + " 申请售后");
    Action delete = new Action("删除订单", "删除订单 " + orderId);
    Action refund = new Action("退款", "订单 " + orderId + " 退款");
    return switch (status) {
      case "PAID" -> List.of(refund);
      case "SHIPPED" -> List.of(logistics, aftersale);
      case "COMPLETED" -> List.of(logistics, aftersale, delete);
      case "CANCELLED", "REFUNDED" -> List.of(delete);
      default -> List.of();
    };
  }

  private static ObjectNode detail(JsonNode out) {
    String orderId = out.path("orderId").asText("");
    ObjectNode screen = UiNodes.screen("order-detail", "订单 " + orderId);
    ObjectNode card = UiNodes.component(screen, "order", "Card");
    card.put("title", "订单 " + orderId);
    ArrayNode items = card.putArray("items");
    for (JsonNode line : out.path("items")) {
      UiNodes.labelValue(
          items,
          "商品",
          line.path("productName").asText("")
              + " × "
              + line.path("quantity").asText("")
              + " = "
              + line.path("amount").asText(""));
    }
    UiNodes.labelValue(items, "订单金额", money(out));
    UiNodes.labelValue(items, "状态", out.path("status").asText(""));
    JsonNode addr = out.path("address");
    UiNodes.labelValue(items, "收货人", addr.path("receiver").asText(""));
    UiNodes.labelValue(items, "手机", addr.path("phoneMasked").asText(""));
    UiNodes.labelValue(items, "地区", addr.path("region").asText(""));
    UiNodes.labelValue(items, "下单时间", time(out.path("createdAt").asText("")));
    if (out.hasNonNull("logistics")) {
      JsonNode lg = out.get("logistics");
      logisticsCard(
          screen,
          orderId,
          lg.path("carrier").asText(""),
          lg.path("trackingNo").asText(""),
          lg.path("status").asText(""));
      // 详情只有概要没有事件列表：给一条「状态」节点，完整轨迹走 order.logistics.get
      ObjectNode tl = UiNodes.component(screen, "logistics-events", "Timeline");
      tl.putArray("items");
      tl.put("emptyText", "点击「查看物流」查看完整轨迹");
    }
    return screen;
  }

  private static ObjectNode logistics(JsonNode out) {
    String orderId = out.path("orderId").asText("");
    ObjectNode screen = UiNodes.screen("logistics", "物流轨迹");
    logisticsCard(
        screen,
        orderId,
        out.path("carrier").asText(""),
        out.path("trackingNo").asText(""),
        out.path("status").asText(""));
    ObjectNode tl = UiNodes.component(screen, "logistics-events", "Timeline");
    ArrayNode items = tl.putArray("items");
    for (JsonNode e : out.path("events")) {
      ObjectNode n = items.addObject();
      n.put("time", e.path("time").asText(""));
      n.put("label", e.path("description").asText(""));
      n.put("description", e.path("location").asText(""));
    }
    if (items.isEmpty()) {
      tl.put("emptyText", "暂无物流信息");
    }
    return screen;
  }

  private static void logisticsCard(
      ObjectNode screen, String orderId, String carrier, String trackingNo, String status) {
    ObjectNode card = UiNodes.component(screen, "logistics", "Card");
    card.put("title", "订单 " + orderId + " 物流");
    ArrayNode items = card.putArray("items");
    UiNodes.labelValue(items, "承运商", carrier);
    UiNodes.labelValue(items, "运单号", trackingNo);
    UiNodes.labelValue(items, "状态", status);
  }

  private static ObjectNode deleted(JsonNode out) {
    ObjectNode screen = UiNodes.screen("delete-result", "订单已删除");
    ObjectNode props = UiNodes.component(screen, "result", "Result");
    props.put("status", "success");
    props.put("title", "订单已删除");
    props.put("description", "该订单已从列表中移除。");
    ArrayNode details = props.putArray("details");
    UiNodes.labelValue(details, "订单号", out.path("orderId").asText(""));
    UiNodes.labelValue(details, "删除时间", time(out.path("deletedAt").asText("")));
    return screen;
  }

  private static String money(JsonNode n) {
    if (n == null || !n.hasNonNull("amount")) {
      return "-";
    }
    return n.get("amount").asText() + " " + UiNodes.text(n, "currency", "CNY");
  }

  static String time(String iso) {
    if (iso.isEmpty()) {
      return "";
    }
    try {
      return TIME.format(Instant.parse(iso));
    } catch (RuntimeException e) {
      return iso;
    }
  }
}
