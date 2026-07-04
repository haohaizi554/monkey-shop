CREATE TABLE membership_profile (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    level VARCHAR(32) NOT NULL,
    growth_value BIGINT NOT NULL DEFAULT 0,
    real_name_encrypted VARCHAR(1024),
    real_name_hmac CHAR(64),
    id_card_encrypted VARCHAR(1024),
    id_card_hmac CHAR(64),
    verified_at DATETIME(6),
    version BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_membership_profile_user UNIQUE (user_id),
    CONSTRAINT fk_membership_profile_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    KEY idx_membership_profile_level_growth (level, growth_value),
    KEY idx_membership_profile_real_name_hmac (real_name_hmac),
    KEY idx_membership_profile_id_card_hmac (id_card_hmac)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE membership_level_history (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    from_level VARCHAR(32) NOT NULL,
    to_level VARCHAR(32) NOT NULL,
    reason VARCHAR(128) NOT NULL,
    operator_user_id BIGINT,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_membership_level_history_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    KEY idx_membership_level_history_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO permissions (name, description)
SELECT p.name, p.description
FROM (
    SELECT 'MEMBERSHIP_READ' AS name, 'Read own membership account' AS description
    UNION ALL SELECT 'MEMBERSHIP_WRITE', 'Update own membership account'
    UNION ALL SELECT 'MEMBERSHIP_ADMIN', 'Manage membership levels'
) p
WHERE NOT EXISTS (SELECT 1 FROM permissions existing WHERE existing.name = p.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN ('MEMBERSHIP_READ', 'MEMBERSHIP_WRITE')
WHERE r.name = 'USER'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN ('MEMBERSHIP_READ', 'MEMBERSHIP_WRITE', 'MEMBERSHIP_ADMIN')
WHERE r.name = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

INSERT INTO membership_profile (id, user_id, level, growth_value, create_time, update_time)
SELECT 3400000000000 + u.id, u.id, 'BASIC', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM `user` u
WHERE NOT EXISTS (SELECT 1 FROM membership_profile mp WHERE mp.user_id = u.id);
