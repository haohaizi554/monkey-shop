ALTER TABLE cart_checkout
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER idempotency_key;

UPDATE cart_checkout
SET request_fingerprint = SHA2(
    CONCAT('legacy:', tenant_id, ':', id, ':', idempotency_key),
    256
)
WHERE request_fingerprint IS NULL;

ALTER TABLE cart_checkout
    MODIFY COLUMN request_fingerprint CHAR(64) NOT NULL;

CREATE TABLE cart_cleanup_intent (
    checkout_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    user_id BIGINT NOT NULL,
    sku_ids VARCHAR(2048) NOT NULL,
    cart_ttl_seconds BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    last_error VARCHAR(255),
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (checkout_id),
    CONSTRAINT fk_cart_cleanup_intent_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_cart_cleanup_intent_checkout_tenant
        FOREIGN KEY (tenant_id, checkout_id) REFERENCES cart_checkout (tenant_id, id),
    CONSTRAINT ck_cart_cleanup_intent_status
        CHECK (status IN ('PENDING', 'COMPLETED')),
    CONSTRAINT ck_cart_cleanup_intent_attempt_count
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_cart_cleanup_intent_ttl
        CHECK (cart_ttl_seconds > 0),
    CONSTRAINT ck_cart_cleanup_intent_completion
        CHECK ((status = 'PENDING' AND completed_at IS NULL)
            OR (status = 'COMPLETED' AND completed_at IS NOT NULL)),
    KEY idx_cart_cleanup_intent_ready (tenant_id, status, next_attempt_at),
    KEY idx_cart_cleanup_intent_user_created (tenant_id, user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
