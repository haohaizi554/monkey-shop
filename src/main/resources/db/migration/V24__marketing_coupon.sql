CREATE TABLE marketing_coupon (
    id BIGINT NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    type VARCHAR(32) NOT NULL,
    threshold_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
    discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
    discount_percent DECIMAL(6,4) NOT NULL DEFAULT 0,
    category_id BIGINT,
    shop_id BIGINT,
    stack_group VARCHAR(32) NOT NULL,
    total_quota INT NOT NULL,
    claimed_count INT NOT NULL DEFAULT 0,
    start_time DATETIME(6) NOT NULL,
    end_time DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_marketing_coupon_code UNIQUE (code),
    CONSTRAINT ck_marketing_coupon_quota CHECK (total_quota >= 0 AND claimed_count >= 0),
    CONSTRAINT ck_marketing_coupon_discount CHECK (
        discount_amount >= 0
        AND discount_percent >= 0
        AND discount_percent <= 1
        AND threshold_amount >= 0
    ),
    KEY idx_marketing_coupon_scope (type, category_id, shop_id),
    KEY idx_marketing_coupon_window (start_time, end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE marketing_user_coupon (
    id BIGINT NOT NULL,
    coupon_id BIGINT NOT NULL,
    coupon_code VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    order_id BIGINT,
    idempotency_key VARCHAR(160) NOT NULL,
    claimed_at DATETIME(6) NOT NULL,
    used_at DATETIME(6),
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_marketing_user_coupon_user_coupon UNIQUE (user_id, coupon_id),
    CONSTRAINT uk_marketing_user_coupon_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_marketing_user_coupon_coupon FOREIGN KEY (coupon_id) REFERENCES marketing_coupon (id),
    KEY idx_marketing_user_coupon_user_status (user_id, status),
    KEY idx_marketing_user_coupon_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO marketing_coupon (
    id, code, name, type, threshold_amount, discount_amount, discount_percent,
    category_id, shop_id, stack_group, total_quota, start_time, end_time
)
VALUES
    (2400000000001, 'PLATFORM-20', 'Platform 20 off', 'THRESHOLD', 100.00, 20.00, 0, NULL, NULL, 'PLATFORM', 10000, CURRENT_TIMESTAMP(6), DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 DAY)),
    (2400000000002, 'SHOP-10', 'Shop 10 off', 'SHOP', 50.00, 10.00, 0, NULL, 1, 'SHOP', 10000, CURRENT_TIMESTAMP(6), DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 DAY))
ON DUPLICATE KEY UPDATE name = VALUES(name), total_quota = VALUES(total_quota);
