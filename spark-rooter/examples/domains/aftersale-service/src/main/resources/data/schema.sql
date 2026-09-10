-- 由 spec feat-commerce-domains §2.2 硬格式约束；check-seed.mjs 解析列名。

CREATE TABLE IF NOT EXISTS `aftersales` (
  `aftersale_id` VARCHAR(64) NOT NULL,
  `order_id` VARCHAR(64) NOT NULL,
  `tenant_id` VARCHAR(64) NOT NULL,
  `type` VARCHAR(16) NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `reason` VARCHAR(500) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`aftersale_id`),
  KEY `idx_aftersales_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

