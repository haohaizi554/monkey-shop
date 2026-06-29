ALTER TABLE `user`
    ADD COLUMN `role` VARCHAR(32) NOT NULL DEFAULT 'USER' AFTER `avatar`,
    ADD COLUMN `nickname` VARCHAR(255) NULL AFTER `role`;

CREATE INDEX `idx_user_role` ON `user` (`role`);

INSERT INTO `user` (
    `username`,
    `password`,
    `phone`,
    `avatar`,
    `role`,
    `nickname`,
    `password_last_changed_at`,
    `create_time`
)
SELECT
    a.`username`,
    a.`password`,
    NULL,
    '/images/default_avatar.png',
    'ADMIN',
    COALESCE(a.`nickname`, a.`username`),
    COALESCE(a.`create_time`, CURRENT_TIMESTAMP(6)),
    COALESCE(a.`create_time`, CURRENT_TIMESTAMP(6))
FROM `admin` a
WHERE NOT EXISTS (
    SELECT 1 FROM `user` u WHERE u.`username` = a.`username`
);
