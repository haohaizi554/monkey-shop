ALTER TABLE cart_checkout
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER idempotency_key;

UPDATE cart_checkout
SET request_fingerprint = 'LEGACY_V51_CHECKOUT_REPLAY_SENTINEL_____________________________'
WHERE request_fingerprint IS NULL;

ALTER TABLE cart_checkout
    MODIFY COLUMN request_fingerprint CHAR(64) NOT NULL;

CREATE TABLE cart_cleanup_intent (
    checkout_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    user_id BIGINT NOT NULL,
    item_snapshots_json LONGTEXT NOT NULL,
    cart_ttl_seconds BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    claim_token VARCHAR(64),
    lease_expires_at DATETIME(6),
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
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED')),
    CONSTRAINT ck_cart_cleanup_intent_attempt_count
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_cart_cleanup_intent_ttl
        CHECK (cart_ttl_seconds > 0),
    CONSTRAINT ck_cart_cleanup_intent_snapshots_json
        CHECK (JSON_VALID(item_snapshots_json)
            AND JSON_TYPE(item_snapshots_json) = 'ARRAY'),
    CONSTRAINT ck_cart_cleanup_intent_claim_state
        CHECK ((status = 'PENDING' AND claim_token IS NULL
                    AND lease_expires_at IS NULL AND completed_at IS NULL)
            OR (status = 'PROCESSING' AND claim_token IS NOT NULL
                    AND lease_expires_at IS NOT NULL AND completed_at IS NULL)
            OR (status = 'COMPLETED' AND claim_token IS NULL
                    AND lease_expires_at IS NULL AND completed_at IS NOT NULL)),
    UNIQUE KEY uk_cart_cleanup_intent_claim (tenant_id, claim_token),
    KEY idx_cart_cleanup_intent_pending_ready (tenant_id, status, next_attempt_at, create_time),
    KEY idx_cart_cleanup_intent_processing_lease (tenant_id, status, lease_expires_at, create_time),
    KEY idx_cart_cleanup_intent_user_created (tenant_id, user_id, create_time),
    KEY idx_cart_cleanup_intent_completed_purge (status, completed_at, checkout_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
