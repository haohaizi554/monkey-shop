CREATE TABLE marketing_group_buy_activity (
    id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    activity_name VARCHAR(128) NOT NULL,
    target_size INT NOT NULL,
    duration_hours INT NOT NULL DEFAULT 24,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_marketing_group_buy_target CHECK (target_size >= 2 AND duration_hours > 0),
    CONSTRAINT fk_marketing_group_buy_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id),
    KEY idx_marketing_group_buy_sku (sku_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE marketing_group_buy_team (
    id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    leader_user_id BIGINT NOT NULL,
    target_size INT NOT NULL,
    joined_count INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_marketing_group_buy_team_count CHECK (joined_count >= 1 AND joined_count <= target_size),
    CONSTRAINT fk_marketing_group_buy_team_activity FOREIGN KEY (activity_id) REFERENCES marketing_group_buy_activity (id),
    CONSTRAINT fk_marketing_group_buy_team_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id),
    KEY idx_marketing_group_buy_team_status (status, expires_at),
    KEY idx_marketing_group_buy_team_activity (activity_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE marketing_group_buy_member (
    id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_marketing_group_buy_member_user UNIQUE (team_id, user_id),
    CONSTRAINT uk_marketing_group_buy_member_idempotency UNIQUE (team_id, idempotency_key),
    CONSTRAINT fk_marketing_group_buy_member_team FOREIGN KEY (team_id) REFERENCES marketing_group_buy_team (id),
    KEY idx_marketing_group_buy_member_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO marketing_group_buy_activity (id, sku_id, activity_name, target_size, duration_hours)
SELECT 2600000000001, ps.id, 'Two-person starter group', 2, 24
FROM product_sku ps
ORDER BY ps.id
LIMIT 1
ON DUPLICATE KEY UPDATE activity_name = VALUES(activity_name), target_size = VALUES(target_size);
