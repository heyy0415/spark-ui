package com.strato.domain.product.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/** 商品聚合。价格 BigDecimal 运算，对外两位小数字符串；specs 为 name/value 列表（存储为 JSON 列）。 */
public record Product(
    String productId,
    String title,
    String description,
    BigDecimal price,
    String currency,
    int stock,
    String category,
    String thumbnail,
    int salesCount,
    List<Spec> specs,
    Instant createdAt) {

  /** 规格项，如 颜色=黑。 */
  public record Spec(String name, String value) {}

  public Product {
    specs = List.copyOf(specs);
  }

  public String priceText() {
    return price.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }
}
