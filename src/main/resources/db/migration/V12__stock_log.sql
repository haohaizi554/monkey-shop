CREATE TABLE IF NOT EXISTS `stock_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `order_id` BIGINT NOT NULL,
    `product_id` BIGINT NOT NULL,
    `direction` VARCHAR(32) NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_stock_log_order_direction` (`order_id`, `direction`),
    KEY `idx_stock_log_product_created_at` (`product_id`, `created_at`)
);
