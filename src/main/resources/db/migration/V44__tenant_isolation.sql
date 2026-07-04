CREATE TABLE tenant (
    id BIGINT NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    plan VARCHAR(32) NOT NULL,
    contact_name VARCHAR(64),
    encrypted_contact_phone VARCHAR(1024),
    contact_phone_hmac CHAR(64),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_tenant_code UNIQUE (code),
    KEY idx_tenant_status_expires (status, expires_at),
    KEY idx_tenant_contact_phone_hmac (contact_phone_hmac)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO tenant (id, code, name, status, plan, contact_name, expires_at)
SELECT 1, 'platform', 'MonkeyShop Platform Tenant', 'ACTIVE', 'ENTERPRISE', 'platform', '2099-12-31 23:59:59.000000'
WHERE NOT EXISTS (SELECT 1 FROM tenant WHERE id = 1);

ALTER TABLE `user` ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE `address` ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE `orders` ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE `monkey` ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE product_spu ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE product_sku ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE product_category ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE product_attribute_template ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE inventory_warehouse ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE inventory_stock ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE inventory_reservation ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE inventory_stock_ledger ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE marketing_coupon ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE marketing_user_coupon ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE marketing_seckill_activity ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE marketing_seckill_order ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE marketing_group_buy_activity ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE marketing_group_buy_team ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE marketing_group_buy_member ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE cart_checkout ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE cart_sub_order ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE cart_checkout_line ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE order_fulfillment_item ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE order_shipment_batch ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE order_shipment_line ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE order_review ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE payment_order ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE payment_ledger ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE payment_callback_log ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE payment_reconciliation_report ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE logistics_tracking ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE logistics_tracking_event ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE logistics_webhook_log ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE logistics_freight_template ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE membership_profile ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE membership_level_history ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE membership_points_wallet ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE membership_points_ledger ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE membership_check_in ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE membership_collection ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE membership_price_drop_event ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE membership_browse_history ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE search_history ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE user_search_profile ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE risk_device_fingerprint ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE risk_score ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE risk_audit_queue ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE tracking_event ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE user_profile_tag ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE product_profile ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE audit_log ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE idempotency_record ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE stock_log ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE visit_log ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;

ALTER TABLE `user` ADD CONSTRAINT fk_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id);
ALTER TABLE `orders` ADD CONSTRAINT fk_orders_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id);
ALTER TABLE product_spu ADD CONSTRAINT fk_product_spu_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id);
ALTER TABLE inventory_stock ADD CONSTRAINT fk_inventory_stock_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id);
ALTER TABLE marketing_coupon ADD CONSTRAINT fk_marketing_coupon_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id);
ALTER TABLE payment_order ADD CONSTRAINT fk_payment_order_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id);
ALTER TABLE logistics_tracking ADD CONSTRAINT fk_logistics_tracking_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id);
ALTER TABLE tracking_event ADD CONSTRAINT fk_tracking_event_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id);

CREATE INDEX idx_user_tenant ON `user` (tenant_id);
CREATE INDEX idx_address_tenant_user ON `address` (tenant_id, user_id);
CREATE INDEX idx_orders_tenant_user_created ON `orders` (tenant_id, user_id, create_time);
CREATE INDEX idx_monkey_tenant_deleted ON `monkey` (tenant_id, deleted);
CREATE INDEX idx_product_spu_tenant_status ON product_spu (tenant_id, status);
CREATE INDEX idx_product_sku_tenant_spu ON product_sku (tenant_id, spu_id);
CREATE INDEX idx_product_category_tenant_parent ON product_category (tenant_id, parent_id);
CREATE INDEX idx_inventory_stock_tenant_sku ON inventory_stock (tenant_id, sku_id, warehouse_id);
CREATE INDEX idx_inventory_reservation_tenant_status ON inventory_reservation (tenant_id, status, expires_at);
CREATE INDEX idx_marketing_coupon_tenant_status ON marketing_coupon (tenant_id, status);
CREATE INDEX idx_cart_checkout_tenant_user ON cart_checkout (tenant_id, user_id, create_time);
CREATE INDEX idx_payment_order_tenant_status_created ON payment_order (tenant_id, status, create_time);
CREATE INDEX idx_logistics_tracking_tenant_order ON logistics_tracking (tenant_id, order_id);
CREATE INDEX idx_membership_profile_tenant_level ON membership_profile (tenant_id, level);
CREATE INDEX idx_search_history_tenant_user ON search_history (tenant_id, user_id, created_at);
CREATE INDEX idx_risk_score_tenant_user ON risk_score (tenant_id, user_id, assessed_at);
CREATE INDEX idx_tracking_event_tenant_type_time ON tracking_event (tenant_id, event_type, occurred_at);
CREATE INDEX idx_audit_log_tenant_created ON audit_log (tenant_id, created_at);
