-- Never silently choose a surviving member when the historical team-scoped key is duplicated.
-- If this preflight fails, repair the groups returned by this query using an operator-reviewed
-- business decision, then rerun Flyway. No member, team count, or team status is changed here.
-- SELECT tenant_id, user_id, idempotency_key,
--        COUNT(*) AS duplicate_count,
--        GROUP_CONCAT(id ORDER BY id) AS member_ids
-- FROM marketing_group_buy_member
-- GROUP BY tenant_id, user_id, idempotency_key
-- HAVING COUNT(*) > 1
-- ORDER BY tenant_id, user_id, idempotency_key;
DROP TEMPORARY TABLE IF EXISTS v57_index_preflight;
DROP TEMPORARY TABLE IF EXISTS v57_duplicate_preflight;

CREATE TEMPORARY TABLE v57_duplicate_preflight (
    duplicate_groups BIGINT NOT NULL,
    CONSTRAINT ck_v57_duplicate_preflight CHECK (duplicate_groups = 0)
) ENGINE = MEMORY;

INSERT INTO v57_duplicate_preflight (duplicate_groups)
SELECT COUNT(*)
FROM (
    SELECT tenant_id, user_id, idempotency_key
    FROM marketing_group_buy_member
    GROUP BY tenant_id, user_id, idempotency_key
    HAVING COUNT(*) > 1
) duplicate_groups;

-- The constraint name is historical. Validate its existing shape before replacing it so a
-- partially applied or drifted schema fails before DDL rather than dropping an unknown index.
-- If this check fails, inspect the named index with:
-- SELECT index_name, non_unique, seq_in_index, column_name
-- FROM information_schema.statistics
-- WHERE table_schema = DATABASE()
--   AND table_name = 'marketing_group_buy_member'
--   AND index_name = 'uk_marketing_group_buy_member_idempotency'
-- ORDER BY seq_in_index;
CREATE TEMPORARY TABLE v57_index_preflight (
    index_present TINYINT NOT NULL,
    old_index_valid TINYINT NOT NULL,
    new_index_valid TINYINT NOT NULL,
    CONSTRAINT ck_v57_index_preflight CHECK (
        index_present = 1 AND (old_index_valid = 1 OR new_index_valid = 1)
    )
) ENGINE = MEMORY;

INSERT INTO v57_index_preflight (index_present, old_index_valid, new_index_valid)
SELECT
    EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'marketing_group_buy_member'
          AND index_name = 'uk_marketing_group_buy_member_idempotency'
    ),
    EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'marketing_group_buy_member'
          AND index_name = 'uk_marketing_group_buy_member_idempotency'
        GROUP BY index_name
        HAVING MAX(non_unique) = 0
           AND COUNT(*) = 2
           AND SUM(seq_in_index = 1 AND column_name = 'team_id') = 1
           AND SUM(seq_in_index = 2 AND column_name = 'idempotency_key') = 1
    ),
    EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'marketing_group_buy_member'
          AND index_name = 'uk_marketing_group_buy_member_idempotency'
        GROUP BY index_name
        HAVING MAX(non_unique) = 0
           AND COUNT(*) = 3
           AND SUM(seq_in_index = 1 AND column_name = 'tenant_id') = 1
           AND SUM(seq_in_index = 2 AND column_name = 'user_id') = 1
           AND SUM(seq_in_index = 3 AND column_name = 'idempotency_key') = 1
    );

SELECT new_index_valid
INTO @v57_new_index_valid
FROM v57_index_preflight;

-- If the corrected index already exists, the migration is a safe no-op. Otherwise replace the
-- validated historical index in one ALTER statement. The duplicate preflight above ran first.
SET @v57_index_sql = IF(
    @v57_new_index_valid = 1,
    'SELECT 1',
    'ALTER TABLE marketing_group_buy_member DROP INDEX `uk_marketing_group_buy_member_idempotency`, ADD CONSTRAINT `uk_marketing_group_buy_member_idempotency` UNIQUE (tenant_id, user_id, idempotency_key)'
);
PREPARE v57_index_statement FROM @v57_index_sql;
EXECUTE v57_index_statement;
DEALLOCATE PREPARE v57_index_statement;

DROP TEMPORARY TABLE v57_index_preflight;
DROP TEMPORARY TABLE v57_duplicate_preflight;
