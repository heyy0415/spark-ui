package com.sparkrooter.examples.product.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sparkrooter.examples.product.domain.Product;
import com.sparkrooter.examples.product.domain.ProductRepository;
import com.sparkrooter.spi.SeedLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/** 从 data/products.json 装配的只读内存仓储；specs 列是 JSON 字符串，加载时解析（格式错误启动即失败）。 */
@Repository
public class SeededProductRepository implements ProductRepository {

  private static final Logger log = LoggerFactory.getLogger(SeededProductRepository.class);
  private static final ObjectMapper SPECS = JsonMapper.builder().build();
  private static final TypeReference<List<Product.Spec>> SPEC_LIST = new TypeReference<>() {};

  /** products.json 行。 */
  record Row(
      String productId,
      String title,
      String description,
      BigDecimal price,
      String currency,
      int stock,
      String category,
      String thumbnail,
      int salesCount,
      String specs,
      Instant createdAt) {}

  private final Map<String, Product> byId;
  private final List<Product> ordered;

  public SeededProductRepository() {
    ordered =
        SeedLoader.load("data/products.json", Row.class).stream()
            .map(SeededProductRepository::assemble)
            .sorted(Comparator.comparing(Product::createdAt))
            .toList();
    byId = ordered.stream().collect(Collectors.toMap(Product::productId, Function.identity()));
    log.info("product seed loaded products={}", ordered.size());
  }

  private static Product assemble(Row r) {
    List<Product.Spec> specs;
    try {
      specs = SPECS.readValue(r.specs(), SPEC_LIST);
    } catch (IOException e) {
      throw new UncheckedIOException("product seed invalid: specs of " + r.productId(), e);
    }
    return new Product(
        r.productId(),
        r.title(),
        r.description(),
        r.price(),
        r.currency(),
        r.stock(),
        r.category(),
        r.thumbnail(),
        r.salesCount(),
        specs,
        r.createdAt());
  }

  @Override
  public Optional<Product> find(String productId) {
    return Optional.ofNullable(byId.get(productId));
  }

  @Override
  public List<Product> findAll() {
    return ordered;
  }
}
