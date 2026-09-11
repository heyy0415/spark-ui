package com.sparkrooter.examples.product.infra;

import com.sparkrooter.examples.product.domain.Product;
import com.sparkrooter.examples.product.domain.ProductRepository;
import com.sparkrooter.spi.annotation.SparkDefault;
import com.sparkrooter.spi.annotation.SparkParam;
import com.sparkrooter.spi.annotation.SparkTool;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 商品域两个工具（@SparkTool 形态）。输出 record 与 change 4 手写 Manifest 一一对应。 */
@Service
public class ProductTools {

  private final ProductRepository products;

  public ProductTools(ProductRepository products) {
    this.products = products;
  }

  public enum Currency {
    CNY
  }

  public record ListIn(
      @SparkParam(description = "标题 / 描述包含的关键词", maxLength = 64) Optional<String> keyword,
      @SparkParam(description = "分类", maxLength = 32) Optional<String> category,
      @SparkParam(
              description = "返回条数",
              unit = {"个", "条", "件"},
              min = 1,
              max = 50)
          @SparkDefault("20")
          Integer limit) {}

  public record ListItem(
      String productId,
      String title,
      BigDecimal price,
      Currency currency,
      @SparkParam(min = 0) int stock,
      String category,
      Optional<String> thumbnail) {}

  public record ListOut(List<ListItem> items, @SparkParam(min = 0) int total) {}

  public record ProductIdIn(
      @SparkParam(
              description = "商品编号",
              entity = "product",
              label = "商品",
              minLength = 1,
              maxLength = 64)
          String productId) {}

  public record Spec(String name, String value) {}

  public record DetailOut(
      String productId,
      String title,
      BigDecimal price,
      Currency currency,
      @SparkParam(min = 0) int stock,
      String category,
      Optional<String> thumbnail,
      String description,
      @SparkParam(min = 0) int salesCount,
      List<Spec> specs,
      Instant createdAt) {}

  @SparkTool(
      id = "product.list.search",
      verbs = {"商品", "有什么卖的", "商品列表", "搜商品"},
      version = "1.0.0",
      domain = "product",
      name = "搜索商品",
      description = "按关键词（标题 / 描述包含）与分类筛选商品目录，默认返回前 20 条（limit 最大 50）。只读，无副作用。",
      clarifiesEntity = "product")
  public ListOut list(ListIn in) {
    String keyword = in.keyword().map(k -> k.trim().toLowerCase(Locale.ROOT)).orElse("");
    List<Product> matched =
        products.findAll().stream()
            .filter(p -> in.category().map(c -> p.category().equals(c)).orElse(true))
            .filter(p -> keyword.isEmpty() || matches(p, keyword))
            .toList();
    int limit = in.limit() == null ? 20 : in.limit();
    return new ListOut(
        matched.stream().limit(limit).map(ProductTools::listItem).toList(), matched.size());
  }

  @SparkTool(
      id = "product.detail.get",
      verbs = {"商品详情", "查看商品", "这个商品"},
      version = "1.0.0",
      domain = "product",
      name = "查询商品详情",
      description = "按商品编号查询商品全部信息：价格、库存、分类、销量与规格列表。只读，无副作用。")
  public DetailOut detail(ProductIdIn in) {
    Product p =
        products
            .find(in.productId())
            .orElseThrow(
                () -> new IllegalArgumentException("product not found: " + in.productId()));
    return new DetailOut(
        p.productId(),
        p.title(),
        p.price(),
        Currency.CNY,
        p.stock(),
        p.category(),
        Optional.ofNullable(p.thumbnail()),
        p.description(),
        p.salesCount(),
        p.specs().stream().map(s -> new Spec(s.name(), s.value())).toList(),
        p.createdAt());
  }

  private static boolean matches(Product p, String keyword) {
    return p.title().toLowerCase(Locale.ROOT).contains(keyword)
        || p.description().toLowerCase(Locale.ROOT).contains(keyword);
  }

  private static ListItem listItem(Product p) {
    return new ListItem(
        p.productId(),
        p.title(),
        p.price(),
        Currency.CNY,
        p.stock(),
        p.category(),
        Optional.ofNullable(p.thumbnail()));
  }
}
