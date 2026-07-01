CREATE TABLE IF NOT EXISTS `roles` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(64) NOT NULL,
    `description` VARCHAR(255),
    `create_time` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_roles_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `permissions` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(128) NOT NULL,
    `description` VARCHAR(255),
    `create_time` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_permissions_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `role_permissions` (
    `role_id` BIGINT NOT NULL,
    `permission_id` BIGINT NOT NULL,
    PRIMARY KEY (`role_id`, `permission_id`),
    CONSTRAINT `fk_role_permissions_role`
        FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_role_permissions_permission`
        FOREIGN KEY (`permission_id`) REFERENCES `permissions` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `user_roles` (
    `user_id` BIGINT NOT NULL,
    `role_id` BIGINT NOT NULL,
    PRIMARY KEY (`user_id`, `role_id`),
    CONSTRAINT `fk_user_roles_user`
        FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_user_roles_role`
        FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `roles` (`name`, `description`)
SELECT 'USER', 'Default customer role'
WHERE NOT EXISTS (SELECT 1 FROM `roles` WHERE `name` = 'USER');

INSERT INTO `roles` (`name`, `description`)
SELECT 'ADMIN', 'Administrator role'
WHERE NOT EXISTS (SELECT 1 FROM `roles` WHERE `name` = 'ADMIN');

INSERT INTO `permissions` (`name`, `description`)
SELECT p.`name`, p.`description`
FROM (
    SELECT 'USER_PROFILE_READ' AS `name`, 'Read own profile' AS `description`
    UNION ALL SELECT 'USER_PROFILE_WRITE', 'Update own profile'
    UNION ALL SELECT 'ADDRESS_MANAGE', 'Manage own addresses'
    UNION ALL SELECT 'ORDER_CREATE', 'Create orders'
    UNION ALL SELECT 'ORDER_READ_OWN', 'Read own orders'
    UNION ALL SELECT 'ORDER_RETURN_REQUEST', 'Request and ship returns for own orders'
    UNION ALL SELECT 'UPLOAD_AVATAR', 'Upload own avatar'
    UNION ALL SELECT 'ADMIN_DASHBOARD_READ', 'Read admin dashboard'
    UNION ALL SELECT 'PRODUCT_MANAGE', 'Create, update, and delete products'
    UNION ALL SELECT 'ORDER_MANAGE', 'Manage all orders and returns'
    UNION ALL SELECT 'UPLOAD_PRODUCT_IMAGE', 'Upload product images'
) p
WHERE NOT EXISTS (SELECT 1 FROM `permissions` existing WHERE existing.`name` = p.`name`);

INSERT INTO `role_permissions` (`role_id`, `permission_id`)
SELECT r.`id`, p.`id`
FROM `roles` r
JOIN `permissions` p ON p.`name` IN (
    'USER_PROFILE_READ',
    'USER_PROFILE_WRITE',
    'ADDRESS_MANAGE',
    'ORDER_CREATE',
    'ORDER_READ_OWN',
    'ORDER_RETURN_REQUEST',
    'UPLOAD_AVATAR'
)
WHERE r.`name` = 'USER'
  AND NOT EXISTS (
      SELECT 1
      FROM `role_permissions` rp
      WHERE rp.`role_id` = r.`id` AND rp.`permission_id` = p.`id`
  );

INSERT INTO `role_permissions` (`role_id`, `permission_id`)
SELECT r.`id`, p.`id`
FROM `roles` r
JOIN `permissions` p
WHERE r.`name` = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1
      FROM `role_permissions` rp
      WHERE rp.`role_id` = r.`id` AND rp.`permission_id` = p.`id`
  );

INSERT INTO `user_roles` (`user_id`, `role_id`)
SELECT u.`id`, r.`id`
FROM `user` u
JOIN `roles` r ON r.`name` = CASE WHEN u.`role` = 'ADMIN' THEN 'ADMIN' ELSE 'USER' END
WHERE NOT EXISTS (
    SELECT 1
    FROM `user_roles` ur
    WHERE ur.`user_id` = u.`id` AND ur.`role_id` = r.`id`
);
