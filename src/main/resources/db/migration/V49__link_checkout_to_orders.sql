ALTER TABLE cart_sub_order
    ADD COLUMN store_discount_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00 AFTER original_amount,
    ADD COLUMN platform_discount_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00 AFTER store_discount_amount,
    ADD COLUMN formal_order_id BIGINT NULL AFTER payable_amount;

UPDATE cart_sub_order
SET store_discount_amount = discount_amount,
    platform_discount_amount = 0.00;

ALTER TABLE `orders`
    ADD COLUMN checkout_id BIGINT NULL AFTER user_id,
    ADD COLUMN checkout_sub_order_id BIGINT NULL AFTER checkout_id,
    ADD COLUMN shop_id BIGINT NULL AFTER checkout_sub_order_id,
    ADD COLUMN original_amount DECIMAL(12, 2) NULL AFTER price,
    ADD COLUMN discount_amount DECIMAL(12, 2) NULL AFTER original_amount,
    ADD COLUMN checkout_idempotency_key VARCHAR(128) NULL AFTER discount_amount,
    ADD CONSTRAINT uk_orders_checkout_sub_order UNIQUE (tenant_id, checkout_sub_order_id),
    ADD CONSTRAINT fk_orders_checkout FOREIGN KEY (checkout_id) REFERENCES cart_checkout (id),
    ADD CONSTRAINT fk_orders_checkout_sub_order FOREIGN KEY (checkout_sub_order_id) REFERENCES cart_sub_order (id);

CREATE INDEX idx_orders_checkout ON `orders` (tenant_id, checkout_id);
CREATE INDEX idx_orders_shop_created ON `orders` (tenant_id, shop_id, create_time);

CREATE TABLE order_line (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    order_id BIGINT NOT NULL,
    checkout_line_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    category_id BIGINT NULL,
    product_name VARCHAR(191) NOT NULL,
    product_image VARCHAR(512) NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(12, 2) NOT NULL,
    original_amount DECIMAL(12, 2) NOT NULL,
    discount_amount DECIMAL(12, 2) NOT NULL,
    payable_amount DECIMAL(12, 2) NOT NULL,
    coupon_codes VARCHAR(512) NULL,
    reservation_key VARCHAR(191) NOT NULL,
    warehouse_id BIGINT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_order_line_checkout_line UNIQUE (tenant_id, checkout_line_id),
    CONSTRAINT uk_order_line_reservation UNIQUE (tenant_id, reservation_key),
    CONSTRAINT fk_order_line_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_order_line_order FOREIGN KEY (order_id) REFERENCES `orders` (id),
    CONSTRAINT fk_order_line_checkout_line FOREIGN KEY (checkout_line_id) REFERENCES cart_checkout_line (id),
    CONSTRAINT ck_order_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_line_amount CHECK (
        unit_price >= 0
        AND original_amount >= 0
        AND discount_amount >= 0
        AND payable_amount >= 0
        AND original_amount = discount_amount + payable_amount
    ),
    KEY idx_order_line_order (tenant_id, order_id),
    KEY idx_order_line_sku (tenant_id, sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE cart_sub_order
    ADD CONSTRAINT uk_cart_sub_order_formal_order UNIQUE (tenant_id, formal_order_id),
    ADD CONSTRAINT fk_cart_sub_order_formal_order FOREIGN KEY (formal_order_id) REFERENCES `orders` (id);
