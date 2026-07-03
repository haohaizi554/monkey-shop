CREATE TABLE cart_checkout (
    id BIGINT NOT NULL,
    checkout_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    address_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    original_amount DECIMAL(12,2) NOT NULL,
    discount_amount DECIMAL(12,2) NOT NULL,
    payable_amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    province VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_cart_checkout_no UNIQUE (checkout_no),
    CONSTRAINT uk_cart_checkout_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_cart_checkout_amount CHECK (
        original_amount >= 0
        AND discount_amount >= 0
        AND payable_amount >= 0
        AND original_amount >= discount_amount
    ),
    KEY idx_cart_checkout_user_created (user_id, create_time),
    KEY idx_cart_checkout_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE cart_sub_order (
    id BIGINT NOT NULL,
    checkout_id BIGINT NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    shop_id BIGINT NOT NULL,
    original_amount DECIMAL(12,2) NOT NULL,
    discount_amount DECIMAL(12,2) NOT NULL,
    payable_amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_cart_sub_order_no UNIQUE (order_no),
    CONSTRAINT fk_cart_sub_order_checkout FOREIGN KEY (checkout_id) REFERENCES cart_checkout (id),
    CONSTRAINT ck_cart_sub_order_amount CHECK (
        original_amount >= 0
        AND discount_amount >= 0
        AND payable_amount >= 0
        AND original_amount >= discount_amount
    ),
    KEY idx_cart_sub_order_checkout (checkout_id),
    KEY idx_cart_sub_order_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE cart_checkout_line (
    id BIGINT NOT NULL,
    checkout_id BIGINT NOT NULL,
    sub_order_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    category_id BIGINT,
    product_name VARCHAR(191) NOT NULL,
    product_image VARCHAR(512),
    quantity INT NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    original_amount DECIMAL(12,2) NOT NULL,
    discount_amount DECIMAL(12,2) NOT NULL,
    payable_amount DECIMAL(12,2) NOT NULL,
    coupon_codes VARCHAR(512),
    reservation_key VARCHAR(191) NOT NULL,
    warehouse_id BIGINT,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_cart_checkout_line_checkout FOREIGN KEY (checkout_id) REFERENCES cart_checkout (id),
    CONSTRAINT fk_cart_checkout_line_sub_order FOREIGN KEY (sub_order_id) REFERENCES cart_sub_order (id),
    CONSTRAINT uk_cart_checkout_line_reservation UNIQUE (reservation_key),
    CONSTRAINT ck_cart_checkout_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_cart_checkout_line_amount CHECK (
        unit_price >= 0
        AND original_amount >= 0
        AND discount_amount >= 0
        AND payable_amount >= 0
        AND original_amount >= discount_amount
    ),
    KEY idx_cart_checkout_line_checkout (checkout_id),
    KEY idx_cart_checkout_line_sku (sku_id),
    KEY idx_cart_checkout_line_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
