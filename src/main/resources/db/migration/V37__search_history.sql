CREATE TABLE search_history (
    id BIGINT NOT NULL,
    user_id BIGINT,
    keyword VARCHAR(128),
    normalized_keyword VARCHAR(128),
    category_id BIGINT,
    filters_json JSON,
    clicked_product_id BIGINT,
    converted TINYINT(1) NOT NULL DEFAULT 0,
    result_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_search_history_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT fk_search_history_category FOREIGN KEY (category_id) REFERENCES product_category (id),
    KEY idx_search_history_keyword_created (normalized_keyword, created_at),
    KEY idx_search_history_user_created (user_id, created_at),
    KEY idx_search_history_clicked_product (clicked_product_id, converted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
