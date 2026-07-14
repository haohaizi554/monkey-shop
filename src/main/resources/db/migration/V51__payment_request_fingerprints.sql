ALTER TABLE payment_order
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER idempotency_key,
    ADD COLUMN payment_url VARCHAR(2048) NULL AFTER request_fingerprint;

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
        UNIQUE (tenant_id, active_order_id);

ALTER TABLE payment_ledger
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER request_key;

UPDATE payment_ledger
SET request_fingerprint = LOWER(SHA2(CONCAT(
    '{"amount":', CAST(amount AS CHAR),
    ',"paymentId":', payment_id,
    ',"reason":""}'
), 256))
WHERE ledger_type = 'REFUND'
  AND request_fingerprint IS NULL;

ALTER TABLE payment_ledger
    ADD CONSTRAINT ck_payment_ledger_refund_fingerprint
        CHECK (ledger_type <> 'REFUND' OR request_fingerprint IS NOT NULL);
