DROP PROCEDURE IF EXISTS assert_v51_no_duplicate_active_payment_intents;

DELIMITER $$

CREATE PROCEDURE assert_v51_no_duplicate_active_payment_intents()
BEGIN
    IF EXISTS (
        SELECT 1
        FROM payment_order
        WHERE status IN ('PENDING', 'PAID', 'PARTIALLY_REFUNDED', 'SUSPENDED')
        GROUP BY tenant_id, order_id
        HAVING COUNT(*) > 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V51 blocked: duplicate active payment intents exist for a tenant/order; resolve them before migration';
    END IF;
END$$

DELIMITER ;

CALL assert_v51_no_duplicate_active_payment_intents();
DROP PROCEDURE assert_v51_no_duplicate_active_payment_intents;

ALTER TABLE payment_order
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER idempotency_key,
    ADD COLUMN operation_state VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNREPLAYABLE' AFTER request_fingerprint,
    ADD COLUMN merchant_token VARCHAR(128) NULL AFTER operation_state,
    ADD COLUMN payment_url VARCHAR(2048) NULL AFTER merchant_token,
    ADD COLUMN response_paid_amount DECIMAL(10, 2) NULL AFTER payment_url,
    ADD COLUMN response_refunded_amount DECIMAL(10, 2) NULL AFTER response_paid_amount,
    ADD COLUMN response_status VARCHAR(32) NULL AFTER response_refunded_amount,
    ADD COLUMN response_provider_trade_no VARCHAR(96) NULL AFTER response_status,
    ADD COLUMN response_paid_at DATETIME(6) NULL AFTER response_provider_trade_no;

UPDATE payment_order
SET request_fingerprint = LOWER(SHA2(CONCAT(
    '{"amount":', CAST(amount AS CHAR),
    ',"currency":"CNY"',
    ',"method":"', method, '"',
    ',"orderId":', order_id,
    '}'
), 256))
WHERE request_fingerprint IS NULL;

ALTER TABLE payment_order
    MODIFY COLUMN request_fingerprint CHAR(64) NOT NULL,
    DROP INDEX uk_payment_order_user_key,
    ADD CONSTRAINT uk_payment_order_user_key
        UNIQUE (tenant_id, user_id, idempotency_key),
    ADD COLUMN active_order_id BIGINT
        GENERATED ALWAYS AS (
            CASE
                WHEN status IN ('PENDING', 'PAID', 'PARTIALLY_REFUNDED', 'SUSPENDED') THEN order_id
                ELSE NULL
            END
        ) STORED,
    ADD CONSTRAINT uk_payment_order_active_order
        UNIQUE (tenant_id, active_order_id),
    ADD CONSTRAINT ck_payment_order_operation_state
        CHECK (
            operation_state = 'LEGACY_UNREPLAYABLE'
            OR (operation_state = 'RESERVED' AND merchant_token IS NOT NULL)
            OR (
                operation_state = 'COMPLETED'
                AND merchant_token IS NOT NULL
                AND response_paid_amount IS NOT NULL
                AND response_refunded_amount IS NOT NULL
                AND response_status IS NOT NULL
            )
        );

ALTER TABLE payment_ledger
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER request_key,
    ADD COLUMN operation_state VARCHAR(32) NULL AFTER request_fingerprint,
    ADD COLUMN merchant_token VARCHAR(128) NULL AFTER operation_state,
    ADD COLUMN response_refunded_amount DECIMAL(10, 2) NULL AFTER provider_trade_no,
    ADD COLUMN response_payment_status VARCHAR(32) NULL AFTER response_refunded_amount,
    ADD COLUMN response_ledger_status VARCHAR(32) NULL AFTER response_payment_status;

UPDATE payment_ledger
SET operation_state = 'LEGACY_UNREPLAYABLE'
WHERE ledger_type = 'REFUND';

ALTER TABLE payment_ledger
    ADD CONSTRAINT ck_payment_ledger_refund_state
        CHECK (ledger_type <> 'REFUND' OR operation_state IS NOT NULL),
    ADD CONSTRAINT ck_payment_ledger_operation_state
        CHECK (
            ledger_type <> 'REFUND'
            OR operation_state = 'LEGACY_UNREPLAYABLE'
            OR (
                operation_state = 'RESERVED'
                AND request_fingerprint IS NOT NULL
                AND merchant_token IS NOT NULL
                AND status = 'ACCEPTED'
            )
            OR (
                operation_state = 'COMPLETED'
                AND request_fingerprint IS NOT NULL
                AND merchant_token IS NOT NULL
                AND status = 'SUCCESS'
                AND response_refunded_amount IS NOT NULL
                AND response_payment_status IS NOT NULL
                AND response_ledger_status IS NOT NULL
            )
        );
