package com.sparkrooter.examples.product.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.examples.product.domain.Product;
import com.sparkrooter.examples.product.domain.ProductRepository;
import com.sparkrooter.spi.ExecutionContext;
import com.sparkrooter.spi.ToolHandler;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** product.list.search@1.0.0：keyword 对 title / description 大小写不敏感包含匹配；category 精确；limit 默认 20。 */
@Component
public class ProductListSearchHandler implements ToolHandler {

  private static final int DEFAULT_LIMIT = 20;

  private final ProductRepository products;
  private final ProductJson json;
  private final ObjectMapper mapper;

  public ProductListSearchHandler(
      ProductRepository products, ProductJson json, ObjectMapper mapper) {
    this.products = products;
    this.json = json;
    this.mapper = mapper;
  }

  @Override
  public String toolId() {
    return "product.list.search";
  }

  @Override
  public String version() {
    return "1.0.0";
  }

  @Override
  public JsonNode handle(JsonNode args, ExecutionContext ctx) {
    String keyword =
        args.hasNonNull("keyword")
            ? args.get("keyword").asText().trim().toLowerCase(Locale.ROOT)
            : "";
    String category = args.hasNonNull("category") ? args.get("category").asText() : null;
    int limit = args.hasNonNull("limit") ? args.get("limit").asInt() : DEFAULT_LIMIT;
    List<Product> matched =
        products.findAll().stream()
            .filter(p -> category == null || p.category().equals(category))
            .filter(p -> keyword.isEmpty() || matches(p, keyword))
            .toList();
    ArrayNode items = mapper.createArrayNode();
    matched.stream().limit(limit).forEach(p -> items.add(json.listItem(p)));
    ObjectNode out = mapper.createObjectNode();
    out.set("items", items);
    out.put("total", matched.size());
    return out;
  }

  private static boolean matches(Product p, String keyword) {
    return p.title().toLowerCase(Locale.ROOT).contains(keyword)
        || p.description().toLowerCase(Locale.ROOT).contains(keyword);
  }
}
