ALTER TABLE `monkey`
    ADD COLUMN `deleted` TINYINT(1) NOT NULL DEFAULT 0;

ALTER TABLE `address`
    ADD COLUMN `deleted` TINYINT(1) NOT NULL DEFAULT 0;

ALTER TABLE `orders`
    ADD COLUMN `deleted` TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN `user_hidden` TINYINT(1) NOT NULL DEFAULT 0;

CREATE INDEX `idx_orders_user_hidden_create_time` ON `orders` (`user_id`, `user_hidden`, `create_time`);
CREATE INDEX `idx_monkey_deleted` ON `monkey` (`deleted`);
CREATE INDEX `idx_address_user_deleted` ON `address` (`user_id`, `deleted`);
