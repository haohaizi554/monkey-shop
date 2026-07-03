CREATE TABLE inventory_warehouse (
    id BIGINT NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    province VARCHAR(64) NOT NULL,
    priority INT NOT NULL DEFAULT 100,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_inventory_warehouse_code UNIQUE (code),
    KEY idx_inventory_warehouse_region (province, active, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_stock (
    id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    available_quantity INT NOT NULL DEFAULT 0,
    locked_quantity INT NOT NULL DEFAULT 0,
    deducted_quantity INT NOT NULL DEFAULT 0,
    in_transit_quantity INT NOT NULL DEFAULT 0,
    safety_stock INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_inventory_stock_sku_warehouse UNIQUE (sku_id, warehouse_id),
    CONSTRAINT ck_inventory_stock_non_negative CHECK (
        available_quantity >= 0
        AND locked_quantity >= 0
        AND deducted_quantity >= 0
        AND in_transit_quantity >= 0
        AND safety_stock >= 0
    ),
    CONSTRAINT fk_inventory_stock_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id),
    CONSTRAINT fk_inventory_stock_warehouse FOREIGN KEY (warehouse_id) REFERENCES inventory_warehouse (id),
    KEY idx_inventory_stock_sku_available (sku_id, available_quantity),
    KEY idx_inventory_stock_warehouse (warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO inventory_warehouse (id, code, name, province, priority)
VALUES
    (2200000000001, 'BJ-01', 'Beijing Warehouse', 'CN-BJ', 10),
    (2200000000002, 'SH-01', 'Shanghai Warehouse', 'CN-SH', 20),
    (2200000000003, 'GZ-01', 'Guangzhou Warehouse', 'CN-GD', 30)
ON DUPLICATE KEY UPDATE name = VALUES(name), province = VALUES(province), priority = VALUES(priority), active = TRUE;
