ALTER TABLE `user`
    ADD COLUMN `password_change_required` TINYINT(1) NOT NULL DEFAULT 0 AFTER `password_last_changed_at`;

CREATE INDEX `idx_user_password_change_required`
    ON `user` (`password_change_required`);
