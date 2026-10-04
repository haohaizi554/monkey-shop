-- Durable logistics creation claims. V60 is owned by membership; this migration intentionally uses V61.
--
-- A legacy tracking row has no claim yet. Before changing the schema, fail closed if the legacy projection has
-- more than one row for a tenant/order: silently choosing one provider token would make the historical state
-- ambiguous. If this preflight fails, inspect and reconcile the rows with:
-- SELECT tenant_id, order_id, GROUP_CONCAT(id ORDER BY id) AS tracking_ids, COUNT(*) AS tracking_count
-- FROM logistics_tracking GROUP BY tenant_id, order_id HAVING COUNT(*) > 1 ORDER BY tenant_id, order_id;
DROP TEMPORARY TABLE IF EXISTS v61_logistics_claim_preflight;

CREATE TEMPORARY TABLE v61_logistics_claim_preflight (
    duplicate_groups BIGINT NOT NULL,
    CONSTRAINT ck_v61_logistics_claim_preflight CHECK (duplicate_groups = 0)
) ENGINE = MEMORY;

INSERT INTO v61_logistics_claim_preflight (duplicate_groups)
SELECT COUNT(*)
FROM (
    SELECT tenant_id, order_id
    FROM logistics_tracking
    GROUP BY tenant_id, order_id
    HAVING COUNT(*) > 1
) duplicate_legacy_orders;

DROP TEMPORARY TABLE v61_logistics_claim_preflight;

ALTER TABLE logistics_tracking
    ADD COLUMN IF NOT EXISTS request_fingerprint CHAR(64) NULL AFTER idempotency_key;

-- The original carrier weight/item count cannot be recovered safely. This deterministic sentinel deliberately
-- makes historical replays conflict while retaining enough identity to bind the old provider projection.
UPDATE logistics_tracking
SET request_fingerprint = LOWER(SHA2(CONCAT(
        'legacy-logistics-v1|tenant=', CAST(tenant_id AS CHAR),
        '|tracking=', CAST(id AS CHAR),
        '|order=', CAST(order_id AS CHAR),
        '|owner=', CAST(user_id AS CHAR),
        '|trackingNo=', tracking_no,
        '|idempotencyKey=', idempotency_key
    ), 256))
WHERE request_fingerprint IS NULL;

CREATE TABLE IF NOT EXISTS logistics_shipment_claim (
    tenant_id BIGINT NOT NULL,
    shipment_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    provider_token VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    claim_token VARCHAR(64) NOT NULL,
    lease_expires_at DATETIME(6) NOT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (shipment_id),
    CONSTRAINT fk_logistics_shipment_claim_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT uk_logistics_shipment_claim_order UNIQUE (tenant_id, order_id),
    CONSTRAINT uk_logistics_shipment_claim_idempotency UNIQUE (tenant_id, owner_user_id, idempotency_key),
    CONSTRAINT uk_logistics_shipment_claim_provider_token UNIQUE (tenant_id, provider_token),
    KEY idx_logistics_shipment_claim_lease (tenant_id, status, lease_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Backfill one accepted claim for every historical projection. The preflight above guarantees that the per-order
-- unique key is deterministic; ON DUPLICATE KEY UPDATE keeps a resumed migration idempotent without deleting or
-- rewriting an existing accepted claim. The tracking number is the stable provider idempotency token.
INSERT INTO logistics_shipment_claim (
    tenant_id,
    shipment_id,
    order_id,
    owner_user_id,
    idempotency_key,
    request_fingerprint,
    provider_token,
    status,
    claim_token,
    lease_expires_at,
    create_time,
    update_time
)
SELECT
    t.tenant_id,
    t.id,
    t.order_id,
    t.user_id,
    t.idempotency_key,
    t.request_fingerprint,
    t.tracking_no,
    'ACCEPTED',
    CONCAT('legacy-logistics-', t.id),
    t.update_time,
    t.create_time,
    t.update_time
FROM logistics_tracking t
ON DUPLICATE KEY UPDATE shipment_id = shipment_id;
