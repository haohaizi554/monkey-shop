ALTER TABLE `monkey`
    MODIFY COLUMN `price` DECIMAL(10, 2);

ALTER TABLE `orders`
    ADD COLUMN `product_id` BIGINT NULL AFTER `buyer_avatar`,
    MODIFY COLUMN `price` DECIMAL(10, 2);

CREATE INDEX `idx_orders_product_id` ON `orders` (`product_id`);
CREATE UNIQUE INDEX `uk_orders_order_no` ON `orders` (`order_no`);
