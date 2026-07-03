CREATE TABLE marketing_seckill_activity (
    id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    activity_name VARCHAR(128) NOT NULL,
    stock_quantity INT NOT NULL,
    sold_quantity INT NOT NULL DEFAULT 0,
    per_user_limit INT NOT NULL DEFAULT 1,
    start_time DATETIME(6) NOT NULL,
    end_time DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_marketing_seckill_stock CHECK (
        stock_quantity >= 0
        AND sold_quantity >= 0
        AND sold_quantity <= stock_quantity
        AND per_user_limit > 0
    ),
    CONSTRAINT fk_marketing_seckill_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id),
    KEY idx_marketing_seckill_window (start_time, end_time),
    KEY idx_marketing_seckill_sku (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE marketing_seckill_order (
    id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    order_id BIGINT,
    quantity INT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_marketing_seckill_user_idempotency UNIQUE (activity_id, user_id, idempotency_key),
    CONSTRAINT ck_marketing_seckill_order_quantity CHECK (quantity > 0),
    CONSTRAINT fk_marketing_seckill_order_activity FOREIGN KEY (activity_id) REFERENCES marketing_seckill_activity (id),
    CONSTRAINT fk_marketing_seckill_order_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id),
    KEY idx_marketing_seckill_order_user (activity_id, user_id),
    KEY idx_marketing_seckill_order_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO marketing_seckill_activity (
    id, sku_id, activity_name, stock_quantity, sold_quantity, per_user_limit, start_time, end_time
)
SELECT 2500000000001, ps.id, 'Launch flash sale', 10, 0, 1, CURRENT_TIMESTAMP(6), DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 7 DAY)
FROM product_sku ps
ORDER BY ps.id
LIMIT 1
ON DUPLICATE KEY UPDATE activity_name = VALUES(activity_name), stock_quantity = VALUES(stock_quantity);
