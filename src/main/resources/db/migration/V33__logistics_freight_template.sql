CREATE TABLE logistics_freight_template (
    id BIGINT NOT NULL,
    carrier VARCHAR(32) NOT NULL,
    province VARCHAR(64) NOT NULL,
    charge_mode VARCHAR(32) NOT NULL,
    base_weight_kg DECIMAL(10, 2) NOT NULL DEFAULT 1.00,
    base_fee DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    step_weight_kg DECIMAL(10, 2) NOT NULL DEFAULT 1.00,
    step_fee DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    item_fee DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    region_fee DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    eta_hours INT NOT NULL,
    active TINYINT(1) NOT NULL DEFAULT 1,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_logistics_freight_template UNIQUE (carrier, province, charge_mode),
    CONSTRAINT ck_logistics_freight_weight CHECK (base_weight_kg > 0 AND step_weight_kg > 0),
    CONSTRAINT ck_logistics_freight_fee CHECK (
        base_fee >= 0
        AND step_fee >= 0
        AND item_fee >= 0
        AND region_fee >= 0
        AND eta_hours > 0
    ),
    KEY idx_logistics_freight_lookup (carrier, province, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO logistics_freight_template (
    id,
    carrier,
    province,
    charge_mode,
    base_weight_kg,
    base_fee,
    step_weight_kg,
    step_fee,
    item_fee,
    region_fee,
    eta_hours
) VALUES
    (3300000000001, 'SF', '*', 'WEIGHT', 1.00, 18.00, 1.00, 6.00, 0.00, 0.00, 24),
    (3300000000002, 'SF', '*', 'ITEM', 1.00, 0.00, 1.00, 0.00, 3.00, 0.00, 24),
    (3300000000003, 'SF', 'Xinjiang', 'REGION', 1.00, 0.00, 1.00, 0.00, 0.00, 12.00, 72),
    (3300000000004, 'ZTO', '*', 'WEIGHT', 1.00, 12.00, 1.00, 4.00, 0.00, 0.00, 36),
    (3300000000005, 'ZTO', '*', 'ITEM', 1.00, 0.00, 1.00, 0.00, 2.00, 0.00, 36),
    (3300000000006, 'ZTO', 'Tibet', 'REGION', 1.00, 0.00, 1.00, 0.00, 0.00, 15.00, 96),
    (3300000000007, 'YTO', '*', 'WEIGHT', 1.00, 10.00, 1.00, 3.50, 0.00, 0.00, 48),
    (3300000000008, 'YTO', '*', 'ITEM', 1.00, 0.00, 1.00, 0.00, 1.50, 0.00, 48),
    (3300000000009, 'YTO', 'Hainan', 'REGION', 1.00, 0.00, 1.00, 0.00, 0.00, 8.00, 72);
