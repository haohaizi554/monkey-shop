ALTER TABLE `user`
    DROP INDEX `idx_user_email`,
    MODIFY COLUMN `email` VARCHAR(1024);