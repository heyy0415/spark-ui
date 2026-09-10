package com.sparkrooter.examples.product.domain;

import java.util.List;
import java.util.Optional;

/** 商品仓储端口；无框架依赖。商品不分租户（全局目录）。 */
public interface ProductRepository {
  Optional<Product> find(String productId);

  /** 全部商品，按 createdAt 升序（目录顺序）。 */
  List<Product> findAll();
}
