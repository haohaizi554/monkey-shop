CREATE TABLE tenant_config (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    config_type VARCHAR(32) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    settings_json JSON NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_tenant_config_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT uk_tenant_config_type_provider UNIQUE (tenant_id, config_type, provider),
    KEY idx_tenant_config_tenant_type (tenant_id, config_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tenant_config_history (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    config_id BIGINT NOT NULL,
    old_settings_json JSON,
    new_settings_json JSON NOT NULL,
    operator_user_id BIGINT,
    changed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_tenant_config_history_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_tenant_config_history_config FOREIGN KEY (config_id) REFERENCES tenant_config (id),
    CONSTRAINT fk_tenant_config_history_operator FOREIGN KEY (operator_user_id) REFERENCES `user` (id),
    KEY idx_tenant_config_history_tenant_changed (tenant_id, changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tenant_rollout_policy (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    rollout_name VARCHAR(96) NOT NULL,
    target_revision VARCHAR(96) NOT NULL,
    canary_weight INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    started_at DATETIME(6),
    completed_at DATETIME(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_tenant_rollout_policy_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT uk_tenant_rollout_name UNIQUE (tenant_id, rollout_name),
    KEY idx_tenant_rollout_status (tenant_id, status, canary_weight)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO permissions (name, description)
SELECT p.name, p.description
FROM (
    SELECT 'TENANT_READ' AS name, 'Read tenant lifecycle, config, billing and rollout data' AS description
    UNION ALL SELECT 'TENANT_WRITE', 'Operate tenant lifecycle, config and data export'
    UNION ALL SELECT 'TENANT_ADMIN', 'Platform tenant administration across all merchants'
) p
WHERE NOT EXISTS (SELECT 1 FROM permissions existing WHERE existing.name = p.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN ('TENANT_READ')
WHERE r.name = 'USER'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN ('TENANT_READ', 'TENANT_WRITE', 'TENANT_ADMIN')
WHERE r.name = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
