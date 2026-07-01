ALTER TABLE `user`
    ADD COLUMN `email` VARCHAR(255) NULL AFTER `phone`;

CREATE INDEX `idx_user_email` ON `user` (`email`);
