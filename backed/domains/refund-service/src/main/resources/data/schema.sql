-- 由 spec feat-commerce-domains §2.2 硬格式约束；check-seed.mjs 解析列名。

CREATE TABLE IF NOT EXISTS `refunds` (
  `refund_id` VARCHAR(64) NOT NULL,
  `order_id` VARCHAR(64) NOT NULL,
  `tenant_id` VARCHAR(64) NOT NULL,
  `amount` DECIMAL(12,2) NOT NULL,
  `currency` CHAR(3) NOT NULL,
  `reason` VARCHAR(32) NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `idempotency_key` VARCHAR(128) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`refund_id`),
  UNIQUE KEY `uk_refunds_idem` (`tenant_id`, `idempotency_key`),
  KEY `idx_refunds_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

