CREATE TABLE membership_points_wallet (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0,
    total_earned BIGINT NOT NULL DEFAULT 0,
    total_spent BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_membership_points_wallet_user UNIQUE (user_id),
    CONSTRAINT fk_membership_points_wallet_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT ck_membership_points_wallet_balance CHECK (balance >= 0 AND total_earned >= 0 AND total_spent >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE membership_points_ledger (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    points BIGINT NOT NULL,
    money_equivalent DECIMAL(12,2) NOT NULL DEFAULT 0,
    order_id BIGINT,
    reference_key VARCHAR(160),
    idempotency_key VARCHAR(160) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_membership_points_ledger_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_membership_points_ledger_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    KEY idx_membership_points_ledger_user_created (user_id, created_at),
    KEY idx_membership_points_ledger_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE membership_check_in (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    check_in_date DATE NOT NULL,
    streak_days INT NOT NULL,
    reward_points BIGINT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_membership_check_in_user_date UNIQUE (user_id, check_in_date),
    CONSTRAINT uk_membership_check_in_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_membership_check_in_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    KEY idx_membership_check_in_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO membership_points_wallet (id, user_id, balance, total_earned, total_spent)
SELECT 3500000000000 + u.id, u.id, 0, 0, 0
FROM `user` u
WHERE NOT EXISTS (SELECT 1 FROM membership_points_wallet w WHERE w.user_id = u.id);
