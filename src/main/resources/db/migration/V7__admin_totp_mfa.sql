ALTER TABLE `user`
    ADD COLUMN `totp_secret` VARCHAR(128) NULL AFTER `password_last_changed_at`,
    ADD COLUMN `mfa_enabled` TINYINT(1) NOT NULL DEFAULT 0 AFTER `totp_secret`;

CREATE INDEX `idx_user_mfa_enabled` ON `user` (`mfa_enabled`);
