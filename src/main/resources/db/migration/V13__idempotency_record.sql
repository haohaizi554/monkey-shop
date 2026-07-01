CREATE TABLE IF NOT EXISTS `idempotency_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `idempotency_key` VARCHAR(128) NOT NULL,
    `request_hash` CHAR(64) NOT NULL,
    `order_id` BIGINT NULL,
    `status` VARCHAR(32) NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `expires_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_idempotency_user_key` (`user_id`, `idempotency_key`),
    KEY `idx_idempotency_order_id` (`order_id`),
    KEY `idx_idempotency_expires_at` (`expires_at`)
);
