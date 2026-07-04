CREATE TABLE payment_order (
    id BIGINT NOT NULL,
    payment_no VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    method VARCHAR(32) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    paid_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    refunded_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    provider_trade_no VARCHAR(96),
    bank_card_ciphertext VARCHAR(1024),
    bank_card_hmac CHAR(64),
    bank_card_last4 VARCHAR(4),
    paid_at DATETIME(6),
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_payment_order_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT uk_payment_order_no UNIQUE (payment_no),
    CONSTRAINT uk_payment_order_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_payment_order_amount CHECK (
        amount > 0
        AND paid_amount >= 0
        AND refunded_amount >= 0
        AND refunded_amount <= paid_amount
    ),
    KEY idx_payment_order_order_user (order_id, user_id),
    KEY idx_payment_order_status_created (status, create_time),
    KEY idx_payment_order_method_paid_at (method, paid_at),
    KEY idx_payment_order_card_hmac (bank_card_hmac)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE payment_ledger (
    id BIGINT NOT NULL,
    payment_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    ledger_type VARCHAR(32) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    provider_trade_no VARCHAR(96),
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_payment_ledger_payment FOREIGN KEY (payment_id) REFERENCES payment_order (id),
    CONSTRAINT fk_payment_ledger_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT uk_payment_ledger_request UNIQUE (payment_id, ledger_type, request_key),
    CONSTRAINT ck_payment_ledger_amount CHECK (amount > 0),
    KEY idx_payment_ledger_order (order_id),
    KEY idx_payment_ledger_user_created (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE payment_callback_log (
    id BIGINT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    payment_no VARCHAR(64) NOT NULL,
    callback_id VARCHAR(128) NOT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_callback_provider_id UNIQUE (provider, callback_id),
    KEY idx_payment_callback_payment (payment_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
