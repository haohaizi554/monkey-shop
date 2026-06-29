ALTER TABLE `user`
    ADD COLUMN `password_last_changed_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) AFTER `password`;

CREATE TABLE IF NOT EXISTS `password_history` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `password_hash` VARCHAR(255) NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX `idx_password_history_user_created_at`
    ON `password_history` (`user_id`, `created_at`);
