CREATE TABLE membership_collection (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    product_image VARCHAR(512),
    last_price DECIMAL(12,2) NOT NULL,
    target_price DECIMAL(12,2),
    price_drop_notified TINYINT(1) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_membership_collection_user_product UNIQUE (user_id, product_id),
    CONSTRAINT fk_membership_collection_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT fk_membership_collection_product FOREIGN KEY (product_id) REFERENCES monkey (id),
    KEY idx_membership_collection_user_created (user_id, create_time),
    KEY idx_membership_collection_price_watch (price_drop_notified, target_price)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE membership_price_drop_event (
    id BIGINT NOT NULL,
    collection_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    old_price DECIMAL(12,2) NOT NULL,
    new_price DECIMAL(12,2) NOT NULL,
    notified_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_membership_price_drop_collection_price UNIQUE (collection_id, new_price),
    CONSTRAINT fk_membership_price_drop_collection FOREIGN KEY (collection_id) REFERENCES membership_collection (id),
    KEY idx_membership_price_drop_user_notified (user_id, notified_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE membership_browse_history (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    product_image VARCHAR(512),
    viewed_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_membership_browse_history_user_product UNIQUE (user_id, product_id),
    CONSTRAINT fk_membership_browse_history_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    KEY idx_membership_browse_history_user_viewed (user_id, viewed_at),
    KEY idx_membership_browse_history_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
