CREATE TABLE inventory_reservation (
    id BIGINT NOT NULL,
    reservation_key VARCHAR(128) NOT NULL,
    sku_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    order_id BIGINT,
    quantity INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_inventory_reservation_key UNIQUE (reservation_key),
    CONSTRAINT ck_inventory_reservation_quantity CHECK (quantity > 0),
    CONSTRAINT fk_inventory_reservation_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id),
    CONSTRAINT fk_inventory_reservation_warehouse FOREIGN KEY (warehouse_id) REFERENCES inventory_warehouse (id),
    KEY idx_inventory_reservation_expiry (status, expires_at),
    KEY idx_inventory_reservation_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_stock_ledger (
    id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    reservation_key VARCHAR(128),
    order_id BIGINT,
    operation VARCHAR(32) NOT NULL,
    quantity INT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_inventory_ledger_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_inventory_ledger_quantity CHECK (quantity > 0),
    CONSTRAINT fk_inventory_ledger_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id),
    CONSTRAINT fk_inventory_ledger_warehouse FOREIGN KEY (warehouse_id) REFERENCES inventory_warehouse (id),
    KEY idx_inventory_ledger_sku_warehouse (sku_id, warehouse_id),
    KEY idx_inventory_ledger_reservation (reservation_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
