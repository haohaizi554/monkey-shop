CREATE TABLE IF NOT EXISTS `admin` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `username` VARCHAR(255),
    `password` VARCHAR(255),
    `nickname` VARCHAR(255),
    `create_time` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `username` VARCHAR(255),
    `password` VARCHAR(255),
    `phone` VARCHAR(255),
    `avatar` VARCHAR(255),
    `create_time` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `monkey` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(255),
    `breed` VARCHAR(255),
    `price` DOUBLE,
    `description` VARCHAR(255),
    `image_url` VARCHAR(255),
    `stock` INT,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `address` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT,
    `receiver_name` VARCHAR(255),
    `phone` VARCHAR(255),
    `detail_address` VARCHAR(255),
    `is_default` INT,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `orders` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `order_no` VARCHAR(255),
    `user_id` BIGINT,
    `buyer_name` VARCHAR(255),
    `buyer_avatar` VARCHAR(255),
    `product_name` VARCHAR(255),
    `product_image` VARCHAR(255),
    `price` DOUBLE,
    `description` VARCHAR(255),
    `receiver_name` VARCHAR(255),
    `receiver_phone` VARCHAR(255),
    `address_snapshot` VARCHAR(255),
    `shipping_time` DATETIME(6),
    `status` VARCHAR(255),
    `create_time` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `visit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `visit_time` DATETIME(6),
    `ip_address` VARCHAR(255),
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
