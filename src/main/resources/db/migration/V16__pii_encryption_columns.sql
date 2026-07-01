ALTER TABLE `user`
    MODIFY COLUMN `phone` VARCHAR(1024),
    ADD COLUMN `phone_hmac` CHAR(64) NULL AFTER `phone`;

ALTER TABLE `address`
    MODIFY COLUMN `receiver_name` VARCHAR(1024),
    MODIFY COLUMN `phone` VARCHAR(1024),
    ADD COLUMN `phone_hmac` CHAR(64) NULL AFTER `phone`,
    MODIFY COLUMN `detail_address` VARCHAR(2048);

ALTER TABLE `orders`
    MODIFY COLUMN `buyer_name` VARCHAR(1024),
    MODIFY COLUMN `receiver_name` VARCHAR(1024),
    MODIFY COLUMN `receiver_phone` VARCHAR(1024),
    ADD COLUMN `receiver_phone_hmac` CHAR(64) NULL AFTER `receiver_phone`,
    MODIFY COLUMN `address_snapshot` VARCHAR(2048);

CREATE INDEX `idx_user_phone_hmac` ON `user` (`phone_hmac`);
CREATE INDEX `idx_address_phone_hmac` ON `address` (`phone_hmac`);
CREATE INDEX `idx_orders_receiver_phone_hmac` ON `orders` (`receiver_phone_hmac`);
