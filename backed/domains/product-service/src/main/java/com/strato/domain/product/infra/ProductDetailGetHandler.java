package com.strato.domain.product.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.strato.domain.product.domain.Product;
import com.strato.domain.product.domain.ProductRepository;
import com.strato.spi.ExecutionContext;
import com.strato.spi.ToolHandler;
import org.springframework.stereotype.Component;

/** product.detail.get@1.0.0。 */
@Component
public class ProductDetailGetHandler implements ToolHandler {

  private final ProductRepository products;
  private final ProductJson json;

  public ProductDetailGetHandler(ProductRepository products, ProductJson json) {
    this.products = products;
    this.json = json;
  }

  @Override
  public String toolId() {
    return "product.detail.get";
  }

  @Override
  public String version() {
    return "1.0.0";
  }

  @Override
  public JsonNode handle(JsonNode args, ExecutionContext ctx) {
    String productId = args.get("productId").asText();
    Product p =
        products
            .find(productId)
            .orElseThrow(() -> new IllegalArgumentException("product not found: " + productId));
    return json.detail(p);
  }
}
