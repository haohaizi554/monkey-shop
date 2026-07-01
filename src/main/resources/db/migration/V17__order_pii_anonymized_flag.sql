ALTER TABLE `orders`
    ADD COLUMN `pii_anonymized` BOOLEAN NOT NULL DEFAULT FALSE AFTER `user_hidden`;

CREATE INDEX `idx_orders_retention_pii_batch`
    ON `orders` (`status`, `create_time`, `pii_anonymized`);
