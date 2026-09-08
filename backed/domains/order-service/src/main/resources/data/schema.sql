-- 由 spec feat-commerce-domains §2.2 硬格式约束；check-seed.mjs 解析列名。

CREATE TABLE IF NOT EXISTS `orders` (
  `order_id` VARCHAR(64) NOT NULL,
  `tenant_id` VARCHAR(64) NOT NULL,
  `user_id` VARCHAR(64) NOT NULL,
  `product_name` VARCHAR(200) NOT NULL,
  `thumbnail` VARCHAR(32) NULL,
  `quantity` INT NOT NULL,
  `amount` DECIMAL(12,2) NOT NULL,
  `currency` CHAR(3) NOT NULL,
  `status` VARCHAR(32) NOT NULL,
  `receiver` VARCHAR(64) NOT NULL,
  `phone_masked` VARCHAR(20) NOT NULL,
  `region` VARCHAR(120) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`order_id`),
  KEY `idx_orders_tenant_created` (`tenant_id`, `created_at`),
  KEY `idx_orders_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `order_items` (
  `item_id` VARCHAR(64) NOT NULL,
  `order_id` VARCHAR(64) NOT NULL,
  `product_id` VARCHAR(64) NOT NULL,
  `product_name` VARCHAR(200) NOT NULL,
  `unit_price` DECIMAL(12,2) NOT NULL,
  `quantity` INT NOT NULL,
  `line_amount` DECIMAL(12,2) NOT NULL,
  PRIMARY KEY (`item_id`),
  KEY `idx_items_order` (`order_id`),
  CONSTRAINT `fk_items_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `logistics_events` (
  `event_id` VARCHAR(64) NOT NULL,
  `order_id` VARCHAR(64) NOT NULL,
  `carrier` VARCHAR(32) NOT NULL,
  `tracking_no` VARCHAR(64) NOT NULL,
  `seq` INT NOT NULL,
  `event_time` DATETIME(3) NOT NULL,
  `location` VARCHAR(80) NOT NULL,
  `description` VARCHAR(200) NOT NULL,
  PRIMARY KEY (`event_id`),
  KEY `idx_logistics_order_seq` (`order_id`, `seq`),
  CONSTRAINT `fk_logistics_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

