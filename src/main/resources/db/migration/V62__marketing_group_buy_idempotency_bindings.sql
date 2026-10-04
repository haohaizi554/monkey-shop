-- Persist the group-buy idempotency decision independently from the member row.
-- Existing member rows are retained. A failed preflight aborts before the target table is created.
-- Repair the rows returned by these checks and rerun Flyway; no member or team data is deleted.
DROP TEMPORARY TABLE IF EXISTS v62_group_buy_binding_preflight;

CREATE TEMPORARY TABLE v62_group_buy_binding_preflight (
    invalid_rows BIGINT NOT NULL,
    duplicate_groups BIGINT NOT NULL,
    CONSTRAINT ck_v62_group_buy_binding_preflight CHECK (invalid_rows = 0 AND duplicate_groups = 0)
) ENGINE = MEMORY;

INSERT INTO v62_group_buy_binding_preflight (invalid_rows, duplicate_groups)
SELECT
    (
        SELECT COUNT(*)
        FROM marketing_group_buy_member m
        LEFT JOIN marketing_group_buy_team t
            ON t.id = m.team_id
           AND t.tenant_id = m.tenant_id
        WHERE t.id IS NULL
           OR CHAR_LENGTH(
                CASE
                    WHEN LEFT(m.idempotency_key, 6) = 'group:' THEN SUBSTRING(m.idempotency_key, 7)
                    ELSE m.idempotency_key
                END
              ) = 0
           OR CHAR_LENGTH(
                CASE
                    WHEN LEFT(m.idempotency_key, 6) = 'group:' THEN SUBSTRING(m.idempotency_key, 7)
                    ELSE m.idempotency_key
                END
              ) > 160
    ),
    (
        SELECT COUNT(*)
        FROM (
            SELECT m.tenant_id,
                   m.user_id,
                   CASE
                       WHEN LEFT(m.idempotency_key, 6) = 'group:' THEN SUBSTRING(m.idempotency_key, 7)
                       ELSE m.idempotency_key
                   END AS normalized_key
            FROM marketing_group_buy_member m
            INNER JOIN marketing_group_buy_team t
                ON t.id = m.team_id
               AND t.tenant_id = m.tenant_id
            GROUP BY m.tenant_id, m.user_id,
                     CASE
                         WHEN LEFT(m.idempotency_key, 6) = 'group:' THEN SUBSTRING(m.idempotency_key, 7)
                         ELSE m.idempotency_key
                     END
            HAVING COUNT(*) > 1
        ) ambiguous_bindings
    );

-- The table is created only after the complete legacy set is known to be unambiguous. This makes
-- the migration operator-repairable and prevents an arbitrary historical row from becoming the
-- authority for a retry.
CREATE TABLE IF NOT EXISTS marketing_group_buy_idempotency_binding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    team_id BIGINT NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_marketing_group_buy_binding_user_key
        UNIQUE (tenant_id, user_id, idempotency_key),
    CONSTRAINT chk_marketing_group_buy_binding_tenant CHECK (tenant_id > 0),
    CONSTRAINT chk_marketing_group_buy_binding_user CHECK (user_id > 0),
    CONSTRAINT chk_marketing_group_buy_binding_team CHECK (team_id > 0),
    CONSTRAINT chk_marketing_group_buy_binding_fingerprint CHECK (request_fingerprint REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT fk_marketing_group_buy_binding_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    KEY idx_marketing_group_buy_binding_team (tenant_id, team_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Backfill the canonical fingerprint used by GroupBuyRequestFingerprint. A leader's historical
-- member row represents the creation form (teamId=null); other members represent an explicit
-- join (teamId=the persisted team). INSERT ... ON DUPLICATE KEY UPDATE is resumable and does not
-- overwrite an already committed binding.
INSERT INTO marketing_group_buy_idempotency_binding (
    tenant_id,
    user_id,
    idempotency_key,
    team_id,
    request_fingerprint,
    created_at,
    updated_at
)
SELECT m.tenant_id,
       m.user_id,
       CASE
           WHEN LEFT(m.idempotency_key, 6) = 'group:' THEN SUBSTRING(m.idempotency_key, 7)
           ELSE m.idempotency_key
       END,
       m.team_id,
       LOWER(SHA2(CONCAT('v1|activityId=', t.activity_id, '|userId=', m.user_id, '|teamId=',
                         CASE WHEN t.leader_user_id = m.user_id THEN 'null' ELSE CAST(m.team_id AS CHAR) END), 256)),
       m.joined_at,
       m.joined_at
FROM marketing_group_buy_member m
INNER JOIN marketing_group_buy_team t
    ON t.id = m.team_id
   AND t.tenant_id = m.tenant_id
ON DUPLICATE KEY UPDATE id = id;

DROP TEMPORARY TABLE v62_group_buy_binding_preflight;
