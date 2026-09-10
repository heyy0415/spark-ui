package com.sparkrooter.examples.product.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.examples.product.domain.Product;
import org.springframework.stereotype.Component;

/** 商品 → 工具输出 JSON 的唯一投影点；字段名与 tool-manifests/*.json outputSchema 一一对应。 */
@Component
public class ProductJson {

  private final ObjectMapper mapper;

  public ProductJson(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** product.list.search 行。 */
  public ObjectNode listItem(Product p) {
    ObjectNode n = mapper.createObjectNode();
    n.put("productId", p.productId());
    n.put("title", p.title());
    n.put("price", p.priceText());
    n.put("currency", p.currency());
    n.put("stock", p.stock());
    n.put("category", p.category());
    n.put("thumbnail", p.thumbnail());
    return n;
  }

  /** product.detail.get 输出：行 + description + salesCount + specs[] + createdAt。 */
  public ObjectNode detail(Product p) {
    ObjectNode n = listItem(p);
    n.put("description", p.description());
    n.put("salesCount", p.salesCount());
    ArrayNode specs = n.putArray("specs");
    p.specs()
        .forEach(
            s -> {
              ObjectNode row = specs.addObject();
              row.put("name", s.name());
              row.put("value", s.value());
            });
    n.put("createdAt", p.createdAt().toString());
    return n;
  }
}
