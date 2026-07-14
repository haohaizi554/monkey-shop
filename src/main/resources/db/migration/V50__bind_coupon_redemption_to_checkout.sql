ALTER TABLE marketing_user_coupon
    ADD COLUMN checkout_id BIGINT NULL AFTER order_id;

UPDATE marketing_user_coupon
SET status = 'REDEEMED'
WHERE status = 'USED';

UPDATE marketing_user_coupon
SET status = 'CLAIMED'
WHERE status = 'RETURNED';

UPDATE marketing_user_coupon muc
SET checkout_id = (
    SELECT o.checkout_id
    FROM `orders` o
    WHERE o.id = muc.order_id
      AND o.tenant_id = muc.tenant_id
      AND o.checkout_id IS NOT NULL
)
WHERE muc.checkout_id IS NULL
  AND muc.order_id IS NOT NULL
  AND EXISTS (
      SELECT 1
      FROM `orders` o
      WHERE o.id = muc.order_id
        AND o.tenant_id = muc.tenant_id
        AND o.checkout_id IS NOT NULL
  );

ALTER TABLE `user`
    ADD CONSTRAINT uk_user_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE marketing_coupon
    ADD CONSTRAINT uk_marketing_coupon_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE cart_checkout
    ADD CONSTRAINT uk_cart_checkout_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE marketing_user_coupon
    DROP INDEX uk_marketing_user_coupon_user_coupon;

ALTER TABLE marketing_user_coupon
    ADD CONSTRAINT uk_marketing_user_coupon_user_coupon UNIQUE (tenant_id, user_id, coupon_id);

ALTER TABLE marketing_user_coupon
    ADD CONSTRAINT uk_marketing_user_coupon_owner_code UNIQUE (tenant_id, user_id, coupon_code);

ALTER TABLE marketing_user_coupon
    DROP INDEX idx_marketing_user_coupon_user_status;

CREATE INDEX idx_marketing_user_coupon_user_status
    ON marketing_user_coupon (tenant_id, user_id, status);

ALTER TABLE marketing_user_coupon
    DROP INDEX idx_marketing_user_coupon_order;

CREATE INDEX idx_marketing_user_coupon_order
    ON marketing_user_coupon (tenant_id, order_id);

CREATE INDEX idx_marketing_user_coupon_checkout
    ON marketing_user_coupon (tenant_id, checkout_id, status);

CREATE INDEX idx_marketing_user_coupon_coupon
    ON marketing_user_coupon (tenant_id, coupon_id);

ALTER TABLE marketing_user_coupon
    ADD CONSTRAINT fk_marketing_user_coupon_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant (id);

ALTER TABLE marketing_user_coupon
    ADD CONSTRAINT fk_marketing_user_coupon_user_tenant
        FOREIGN KEY (tenant_id, user_id) REFERENCES `user` (tenant_id, id);

ALTER TABLE marketing_user_coupon
    ADD CONSTRAINT fk_marketing_user_coupon_coupon_tenant
        FOREIGN KEY (tenant_id, coupon_id) REFERENCES marketing_coupon (tenant_id, id);

ALTER TABLE marketing_user_coupon
    ADD CONSTRAINT fk_marketing_user_coupon_checkout_tenant
        FOREIGN KEY (tenant_id, checkout_id) REFERENCES cart_checkout (tenant_id, id);
