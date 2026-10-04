-- shop_id is the tenant-local commerce partition used to group sub-orders and scope shop coupons.
-- It is not a merchant-ownership or authorization identifier: the current authorization model is
-- tenant-wide PRODUCT_MANAGE and has no shop/member aggregate. A future multi-merchant model must
-- introduce that aggregate and its authorization rules before treating this column as ownership.
--
-- Do not mutate product_spu until every legacy row has an unambiguous positive BIGINT shopId.
-- If this preflight fails, repair the rows returned by the following query and rerun Flyway:
-- SELECT id, tenant_id, attributes_json
-- FROM product_spu
-- WHERE NOT (
--     COALESCE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(attributes_json, '$.shopId'))), '') REGEXP '^[1-9][0-9]*$'
--     AND (
--         CHAR_LENGTH(COALESCE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(attributes_json, '$.shopId'))), '')) < 19
--         OR (
--             CHAR_LENGTH(COALESCE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(attributes_json, '$.shopId'))), '')) = 19
--             AND COALESCE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(attributes_json, '$.shopId'))), '') <= '9223372036854775807'
--         )
--     )
-- )
-- ORDER BY id;
DROP TEMPORARY TABLE IF EXISTS v56_shop_id_preflight;

CREATE TEMPORARY TABLE v56_shop_id_preflight (
    unknown_rows BIGINT NOT NULL,
    CONSTRAINT ck_v56_shop_id_preflight CHECK (unknown_rows = 0)
) ENGINE = MEMORY;

INSERT INTO v56_shop_id_preflight (unknown_rows)
SELECT COUNT(*)
FROM product_spu
WHERE NOT (
    COALESCE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(attributes_json, '$.shopId'))), '') REGEXP '^[1-9][0-9]*$'
    AND (
        CHAR_LENGTH(COALESCE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(attributes_json, '$.shopId'))), '')) < 19
        OR (
            CHAR_LENGTH(COALESCE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(attributes_json, '$.shopId'))), '')) = 19
            AND COALESCE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(attributes_json, '$.shopId'))), '') <= '9223372036854775807'
        )
    )
);

-- MySQL 8.0.41 supports IF NOT EXISTS, so a failed backfill can be resumed after repair.
ALTER TABLE product_spu
    ADD COLUMN IF NOT EXISTS shop_id BIGINT NULL AFTER category_id;

UPDATE product_spu
SET shop_id = CAST(TRIM(JSON_UNQUOTE(JSON_EXTRACT(attributes_json, '$.shopId'))) AS UNSIGNED)
WHERE shop_id IS NULL;

ALTER TABLE product_spu
    MODIFY COLUMN shop_id BIGINT NOT NULL,
    ADD CONSTRAINT chk_product_spu_shop_id CHECK (shop_id > 0),
    ADD INDEX idx_product_spu_tenant_shop_status (tenant_id, shop_id, status);

DROP TEMPORARY TABLE v56_shop_id_preflight;
