CREATE TABLE user_search_profile (
    user_id BIGINT NOT NULL,
    encrypted_interest_profile VARCHAR(2048),
    interest_profile_hmac CHAR(64),
    tag_vector_json JSON,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_search_profile_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    KEY idx_user_search_profile_interest_hmac (interest_profile_hmac),
    KEY idx_user_search_profile_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO permissions (name, description)
SELECT p.name, p.description
FROM (
    SELECT 'SEARCH_READ' AS name, 'Read personalized search recommendations' AS description
    UNION ALL SELECT 'SEARCH_WRITE', 'Update search profile and conversion signals'
    UNION ALL SELECT 'SEARCH_ADMIN', 'Operate search hot keyword jobs'
) p
WHERE NOT EXISTS (SELECT 1 FROM permissions existing WHERE existing.name = p.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN ('SEARCH_READ', 'SEARCH_WRITE')
WHERE r.name = 'USER'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN ('SEARCH_READ', 'SEARCH_WRITE', 'SEARCH_ADMIN')
WHERE r.name = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
