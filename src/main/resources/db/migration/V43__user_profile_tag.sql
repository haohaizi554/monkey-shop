CREATE TABLE user_profile_tag (
    user_id BIGINT NOT NULL,
    encrypted_profile_summary VARCHAR(2048),
    profile_summary_hmac CHAR(64),
    behavior_tags_json JSON,
    interest_tags_json JSON,
    last_event_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_profile_tag_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    KEY idx_user_profile_tag_summary_hmac (profile_summary_hmac),
    KEY idx_user_profile_tag_last_event (last_event_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE product_profile (
    product_id BIGINT NOT NULL,
    category_id BIGINT,
    tag_vector_json JSON,
    sales_count BIGINT NOT NULL DEFAULT 0,
    review_score DECIMAL(5, 2) NOT NULL DEFAULT 0,
    last_event_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (product_id),
    CONSTRAINT fk_product_profile_product FOREIGN KEY (product_id) REFERENCES product_spu (id),
    KEY idx_product_profile_category (category_id),
    KEY idx_product_profile_sales (sales_count),
    KEY idx_product_profile_last_event (last_event_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
