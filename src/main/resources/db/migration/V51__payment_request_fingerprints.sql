DROP TEMPORARY TABLE IF EXISTS v51_active_payment_preflight;

CREATE TEMPORARY TABLE v51_active_payment_preflight (
    duplicate_group_count BIGINT NOT NULL,
    CONSTRAINT ck_v51_resolve_duplicate_active_payment_intents
        CHECK (duplicate_group_count = 0)
);

INSERT INTO v51_active_payment_preflight (duplicate_group_count)
SELECT COUNT(*)
FROM (
    SELECT tenant_id, order_id
    FROM payment_order
    WHERE status IN ('PENDING', 'PAID', 'PARTIALLY_REFUNDED', 'SUSPENDED')
    GROUP BY tenant_id, order_id
    HAVING COUNT(*) > 1
) duplicate_active_payment_groups;

DROP TEMPORARY TABLE v51_active_payment_preflight;

ALTER TABLE payment_order
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER idempotency_key,
    ADD COLUMN operation_state VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNREPLAYABLE' AFTER request_fingerprint,
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0 AFTER operation_state,
    ADD COLUMN lease_expires_at DATETIME(6) NULL AFTER attempt_count,
    ADD COLUMN last_failure_classification VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNKNOWN' AFTER lease_expires_at,
    ADD COLUMN terminal_failure_code VARCHAR(64) NULL AFTER last_failure_classification,
    ADD COLUMN merchant_token VARCHAR(128) NULL AFTER terminal_failure_code,
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
    ), 256)),
    operation_state = 'LEGACY_UNREPLAYABLE',
    attempt_count = 0,
    lease_expires_at = NULL,
    last_failure_classification = 'LEGACY_UNKNOWN',
    terminal_failure_code = NULL;

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
    ADD INDEX idx_payment_order_operation_lease
        (tenant_id, operation_state, lease_expires_at),
    ADD CONSTRAINT ck_payment_order_failed_not_recoverable
        CHECK (status <> 'FAILED' OR operation_state NOT IN ('RESERVED', 'RETRYABLE')),
    ADD CONSTRAINT ck_payment_order_operation_state
        CHECK (
            (
                operation_state = 'LEGACY_UNREPLAYABLE'
                AND attempt_count = 0
                AND lease_expires_at IS NULL
                AND last_failure_classification = 'LEGACY_UNKNOWN'
                AND terminal_failure_code IS NULL
            )
            OR (
                operation_state IN ('RESERVED', 'RETRYABLE')
                AND attempt_count >= 1
                AND lease_expires_at IS NOT NULL
                AND terminal_failure_code IS NULL
                AND merchant_token IS NOT NULL
                AND (
                    operation_state = 'RESERVED'
                    OR last_failure_classification IN ('TIMEOUT_UNKNOWN', 'UNKNOWN', 'LOCAL_COMPLETION')
                )
            )
            OR (
                operation_state = 'COMPLETED'
                AND attempt_count >= 1
                AND lease_expires_at IS NULL
                AND terminal_failure_code IS NULL
                AND merchant_token IS NOT NULL
                AND response_paid_amount IS NOT NULL
                AND response_refunded_amount IS NOT NULL
                AND response_status IS NOT NULL
            )
            OR (
                operation_state = 'TERMINAL_FAILED'
                AND attempt_count >= 1
                AND lease_expires_at IS NULL
                AND last_failure_classification = 'PROVIDER_REJECTED'
                AND terminal_failure_code IS NOT NULL
                AND terminal_failure_code IN ('PROVIDER_REJECTED', 'CARD_DECLINED', 'REFUND_DECLINED')
                AND merchant_token IS NOT NULL
                AND status = 'FAILED'
            )
        );

ALTER TABLE payment_ledger
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER request_key,
    ADD COLUMN operation_state VARCHAR(32) NULL AFTER request_fingerprint,
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0 AFTER operation_state,
    ADD COLUMN lease_expires_at DATETIME(6) NULL AFTER attempt_count,
    ADD COLUMN last_failure_classification VARCHAR(32) NOT NULL DEFAULT 'NONE' AFTER lease_expires_at,
    ADD COLUMN terminal_failure_code VARCHAR(64) NULL AFTER last_failure_classification,
    ADD COLUMN merchant_token VARCHAR(128) NULL AFTER terminal_failure_code,
    ADD COLUMN response_refunded_amount DECIMAL(10, 2) NULL AFTER provider_trade_no,
    ADD COLUMN response_payment_status VARCHAR(32) NULL AFTER response_refunded_amount,
    ADD COLUMN response_ledger_status VARCHAR(32) NULL AFTER response_payment_status,
    ADD COLUMN audit_state VARCHAR(32) NOT NULL DEFAULT 'NONE' AFTER response_ledger_status,
    ADD COLUMN audit_event_type VARCHAR(64) NULL AFTER audit_state,
    ADD COLUMN audit_actor_user_id BIGINT NULL AFTER audit_event_type,
    ADD COLUMN audit_actor_role VARCHAR(32) NULL AFTER audit_actor_user_id,
    ADD COLUMN audit_source_ip VARCHAR(64) NULL AFTER audit_actor_role,
    ADD COLUMN audit_include_owner BOOLEAN NOT NULL DEFAULT FALSE AFTER audit_source_ip,
    ADD COLUMN audit_detail VARCHAR(255) NULL AFTER audit_include_owner;

UPDATE payment_ledger
SET operation_state = 'LEGACY_UNREPLAYABLE',
    attempt_count = 0,
    lease_expires_at = NULL,
    last_failure_classification = 'LEGACY_UNKNOWN',
    terminal_failure_code = NULL,
    audit_state = 'NONE',
    audit_include_owner = FALSE
WHERE ledger_type = 'REFUND';

ALTER TABLE payment_ledger
    ADD INDEX idx_payment_ledger_operation_lease
        (tenant_id, ledger_type, operation_state, lease_expires_at),
    ADD INDEX idx_payment_ledger_audit_pending
        (tenant_id, ledger_type, audit_state, create_time),
    ADD CONSTRAINT ck_payment_ledger_refund_state
        CHECK (ledger_type <> 'REFUND' OR operation_state IS NOT NULL),
    ADD CONSTRAINT ck_payment_ledger_non_refund_operation
        CHECK (
            ledger_type = 'REFUND'
            OR (
                operation_state IS NULL
                AND attempt_count = 0
                AND lease_expires_at IS NULL
                AND last_failure_classification = 'NONE'
                AND terminal_failure_code IS NULL
            )
        ),
    ADD CONSTRAINT ck_payment_ledger_operation_state
        CHECK (
            ledger_type <> 'REFUND'
            OR (
                operation_state = 'LEGACY_UNREPLAYABLE'
                AND attempt_count = 0
                AND lease_expires_at IS NULL
                AND last_failure_classification = 'LEGACY_UNKNOWN'
                AND terminal_failure_code IS NULL
                AND audit_state = 'NONE'
            )
            OR (
                operation_state IN ('RESERVED', 'RETRYABLE')
                AND attempt_count >= 1
                AND lease_expires_at IS NOT NULL
                AND terminal_failure_code IS NULL
                AND request_fingerprint IS NOT NULL
                AND merchant_token IS NOT NULL
                AND status = 'ACCEPTED'
                AND audit_state = 'WAITING'
                AND (
                    operation_state = 'RESERVED'
                    OR last_failure_classification IN ('TIMEOUT_UNKNOWN', 'UNKNOWN', 'LOCAL_COMPLETION')
                )
            )
            OR (
                operation_state = 'COMPLETED'
                AND attempt_count >= 1
                AND lease_expires_at IS NULL
                AND terminal_failure_code IS NULL
                AND request_fingerprint IS NOT NULL
                AND merchant_token IS NOT NULL
                AND status = 'SUCCESS'
                AND response_refunded_amount IS NOT NULL
                AND response_payment_status IS NOT NULL
                AND response_ledger_status IS NOT NULL
                AND audit_state IN ('PENDING', 'DELIVERED')
            )
            OR (
                operation_state = 'TERMINAL_FAILED'
                AND attempt_count >= 1
                AND lease_expires_at IS NULL
                AND last_failure_classification = 'PROVIDER_REJECTED'
                AND terminal_failure_code IS NOT NULL
                AND terminal_failure_code IN ('PROVIDER_REJECTED', 'CARD_DECLINED', 'REFUND_DECLINED')
                AND request_fingerprint IS NOT NULL
                AND merchant_token IS NOT NULL
                AND status = 'FAILED'
                AND audit_state = 'WAITING'
            )
        ),
    ADD CONSTRAINT ck_payment_ledger_audit_state
        CHECK (
            (
                audit_state = 'NONE'
                AND audit_event_type IS NULL
                AND audit_actor_user_id IS NULL
                AND audit_actor_role IS NULL
                AND audit_source_ip IS NULL
                AND audit_include_owner = FALSE
                AND audit_detail IS NULL
            )
            OR (
                audit_state = 'WAITING'
                AND audit_event_type IS NOT NULL
                AND audit_actor_role IS NOT NULL
                AND audit_detail IS NULL
            )
            OR (
                audit_state IN ('PENDING', 'DELIVERED')
                AND audit_event_type IS NOT NULL
                AND audit_actor_role IS NOT NULL
                AND audit_detail IS NOT NULL
            )
        );
