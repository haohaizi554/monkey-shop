CREATE TABLE IF NOT EXISTS `audit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `event_type` VARCHAR(64) NOT NULL,
    `outcome` VARCHAR(32) NOT NULL,
    `actor_user_id` BIGINT NULL,
    `actor_role` VARCHAR(32) NULL,
    `subject_hash` CHAR(64) NULL,
    `source_ip` VARCHAR(64) NULL,
    `detail` VARCHAR(255) NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    INDEX `idx_audit_log_created_at` (`created_at`),
    INDEX `idx_audit_log_event_created_at` (`event_type`, `created_at`),
    INDEX `idx_audit_log_actor_created_at` (`actor_user_id`, `created_at`),
    INDEX `idx_audit_log_subject_created_at` (`subject_hash`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
