CREATE TABLE order_review (
    id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    rating INT NOT NULL,
    content VARCHAR(1000),
    image_urls VARCHAR(2048),
    anonymous TINYINT(1) NOT NULL DEFAULT 0,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_order_review_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT uk_order_review_user_order_sku UNIQUE (user_id, order_id, sku_id),
    CONSTRAINT ck_order_review_rating CHECK (rating BETWEEN 1 AND 5),
    KEY idx_order_review_order (order_id),
    KEY idx_order_review_sku (sku_id),
    KEY idx_order_review_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
