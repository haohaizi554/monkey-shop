-- Preflight evidence: keep the legacy verified_at value and count rows before mapping.
SELECT COUNT(*) AS membership_profile_rows_before_identity_migration,
       COALESCE(SUM(verified_at IS NOT NULL), 0) AS legacy_verified_rows
FROM membership_profile;

ALTER TABLE membership_profile
    ADD COLUMN identity_status VARCHAR(16) NULL AFTER id_card_hmac,
    ADD COLUMN identity_submitted_at DATETIME(6) NULL AFTER identity_status,
    ADD COLUMN identity_reviewed_at DATETIME(6) NULL AFTER identity_submitted_at,
    ADD COLUMN identity_reviewed_by BIGINT NULL AFTER identity_reviewed_at,
    ADD COLUMN identity_review_reason VARCHAR(256) NULL AFTER identity_reviewed_by;

-- verified_at is deliberately retained as the recovery source for legacy rows.
UPDATE membership_profile
SET identity_status = CASE WHEN verified_at IS NULL THEN 'PENDING' ELSE 'VERIFIED' END,
    identity_submitted_at = CASE WHEN verified_at IS NULL THEN NULL ELSE verified_at END,
    identity_reviewed_at = CASE WHEN verified_at IS NULL THEN NULL ELSE verified_at END,
    identity_reviewed_by = NULL,
    identity_review_reason = CASE
        WHEN verified_at IS NULL THEN NULL
        ELSE 'legacy_verified_at_migrated'
    END
WHERE identity_status IS NULL;

ALTER TABLE membership_profile
    MODIFY COLUMN identity_status VARCHAR(16) NOT NULL DEFAULT 'PENDING';

ALTER TABLE membership_points_ledger
    ADD COLUMN mutation_fingerprint CHAR(64) NULL AFTER idempotency_key;

-- Backfill the canonical, tenant-bound intent fingerprint before enforcing persistence.
UPDATE membership_points_ledger
SET mutation_fingerprint = LOWER(SHA2(CONCAT(
        OCTET_LENGTH(CAST(tenant_id AS CHAR)), ':', CAST(tenant_id AS CHAR), '|',
        OCTET_LENGTH(CAST(user_id AS CHAR)), ':', CAST(user_id AS CHAR), '|',
        OCTET_LENGTH(type), ':', type, '|',
        OCTET_LENGTH(CAST(points AS CHAR)), ':', CAST(points AS CHAR), '|',
        OCTET_LENGTH(IFNULL(CAST(order_id AS CHAR), '')), ':', IFNULL(CAST(order_id AS CHAR), ''), '|',
        OCTET_LENGTH(IFNULL(TRIM(reference_key), '')), ':', IFNULL(TRIM(reference_key), ''), '|',
        OCTET_LENGTH(idempotency_key), ':', idempotency_key
    ), 256))
WHERE mutation_fingerprint IS NULL;

ALTER TABLE membership_points_ledger
    MODIFY COLUMN mutation_fingerprint CHAR(64) NOT NULL,
    DROP INDEX uk_membership_points_ledger_idempotency,
    ADD CONSTRAINT uk_membership_points_ledger_idempotency
        UNIQUE (tenant_id, user_id, idempotency_key);
