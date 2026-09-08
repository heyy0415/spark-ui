package com.strato.domain.product.infra.screen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.strato.spi.ScreenBuilder;
import com.strato.spi.ScreenContext;
import com.strato.spi.UiNodes;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 商品域屏：列表 [products: Table]（行内「查看商品」）；详情 [product: Card]。无需确认工具。 */
@Component
public class ProductScreens implements ScreenBuilder {

  public static final String VIEW_LABEL = "查看商品";

  @Override
  public Set<String> resultToolIds() {
    return Set.of("product.list.search", "product.detail.get");
  }

  @Override
  public Set<String> confirmToolIds() {
    return Set.of();
  }

  @Override
  public JsonNode result(String toolId, JsonNode out, ScreenContext ctx) {
    return "product.list.search".equals(toolId) ? list(out) : detail(out);
  }

  @Override
  public JsonNode confirmation(
      String toolId,
      Map<String, String> fixedArgs,
      Map<String, JsonNode> previousOutputs,
      String token,
      ScreenContext ctx) {
    throw new IllegalArgumentException("product domain has no confirmation screens");
  }

  @Override
  public String summary(String toolId, JsonNode out) {
    return switch (toolId) {
      case "product.list.search" -> "找到 " + out.path("total").asInt() + " 件商品";
      case "product.detail.get" -> "商品 " + out.path("title").asText("");
      default -> null;
    };
  }

  /** 行内指令：查看商品 → 「查看商品 <id> 的详情」（label ↔ 动词映射见 contracts.md §4）。 */
  public static String viewIntent(String productId) {
    return "查看商品 " + productId + " 的详情";
  }

  private static ObjectNode list(JsonNode out) {
    ObjectNode screen = UiNodes.screen("product-list", "商品列表");
    ObjectNode table = UiNodes.component(screen, "products", "Table");
    ArrayNode columns = table.putArray("columns");
    for (String[] c :
        new String[][] {
          {"productId", "编号"},
          {"title", "商品"},
          {"price", "价格"},
          {"stock", "库存"},
          {"category", "分类"}
        }) {
      ObjectNode col = columns.addObject();
      col.put("key", c[0]);
      col.put("title", c[1]);
    }
    ArrayNode rows = table.putArray("rows");
    for (JsonNode it : out.path("items")) {
      String id = it.path("productId").asText();
      ObjectNode row = rows.addObject();
      row.put("id", id);
      ObjectNode cells = row.putObject("cells");
      cells.put("productId", id);
      cells.put("title", it.path("title").asText(""));
      cells.put("price", it.path("price").asText("") + " " + it.path("currency").asText("CNY"));
      cells.put("stock", it.path("stock").asText(""));
      cells.put("category", it.path("category").asText(""));
      UiNodes.inlineAction(row.putArray("actions"), VIEW_LABEL, viewIntent(id));
    }
    int total = out.path("total").asInt();
    table.put("total", total);
    if (rows.isEmpty()) {
      table.put("emptyText", "没有找到商品");
    } else if (total > rows.size()) {
      table.put("emptyText", "共 " + total + " 件，仅展示 " + rows.size() + " 件");
    }
    return screen;
  }

  private static ObjectNode detail(JsonNode out) {
    ObjectNode screen = UiNodes.screen("product-detail", "商品详情");
    ObjectNode card = UiNodes.component(screen, "product", "Card");
    card.put("title", UiNodes.truncate(out.path("title").asText(""), 80));
    card.put("description", UiNodes.truncate(out.path("description").asText(""), 500));
    ArrayNode items = card.putArray("items");
    UiNodes.labelValue(items, "编号", out.path("productId").asText(""));
    UiNodes.labelValue(
        items, "价格", out.path("price").asText("") + " " + out.path("currency").asText("CNY"));
    int stock = out.path("stock").asInt();
    ObjectNode st = items.addObject();
    st.put("label", "库存");
    st.put("value", stock == 0 ? "缺货" : String.valueOf(stock));
    st.put("tone", stock == 0 ? "danger" : "default");
    UiNodes.labelValue(items, "分类", out.path("category").asText(""));
    UiNodes.labelValue(items, "销量", out.path("salesCount").asText(""));
    for (JsonNode spec : out.path("specs")) {
      UiNodes.labelValue(items, spec.path("name").asText(""), spec.path("value").asText(""));
    }
    return screen;
  }
}
