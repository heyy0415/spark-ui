-- 由 spec feat-commerce-domains §2.2 硬格式约束；check-seed.mjs 解析列名。

CREATE TABLE IF NOT EXISTS `products` (
  `product_id` VARCHAR(64) NOT NULL,
  `title` VARCHAR(200) NOT NULL,
  `description` VARCHAR(500) NOT NULL,
  `price` DECIMAL(12,2) NOT NULL,
  `currency` CHAR(3) NOT NULL,
  `stock` INT NOT NULL,
  `category` VARCHAR(32) NOT NULL,
  `thumbnail` VARCHAR(32) NULL,
  `sales_count` INT NOT NULL,
  `specs` JSON NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`product_id`),
  KEY `idx_products_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

