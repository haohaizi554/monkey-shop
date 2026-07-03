CREATE TABLE product_spu (
    id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    original_price DECIMAL(10, 2) NOT NULL,
    member_price DECIMAL(10, 2) NULL,
    strike_price DECIMAL(10, 2) NULL,
    region_prices_json JSON NULL,
    attributes_json JSON NULL,
    detail_json_ld JSON NULL,
    supplier_private_remark VARCHAR(2048) NULL,
    image_url VARCHAR(512) NULL,
    deleted BIT(1) NOT NULL DEFAULT b'0',
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT chk_product_spu_status CHECK (
        status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'LISTED', 'UNLISTED', 'RECYCLED')
    ),
    CONSTRAINT chk_product_spu_original_price CHECK (original_price > 0),
    INDEX idx_product_spu_category_status (category_id, status),
    INDEX idx_product_spu_deleted_status (deleted, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE product_sku (
    id BIGINT NOT NULL,
    spu_id BIGINT NOT NULL,
    sku_code VARCHAR(191) NOT NULL,
    spec_json JSON NOT NULL,
    original_price DECIMAL(10, 2) NOT NULL,
    member_price DECIMAL(10, 2) NULL,
    strike_price DECIMAL(10, 2) NULL,
    region_prices_json JSON NULL,
    active BIT(1) NOT NULL DEFAULT b'1',
    PRIMARY KEY (id),
    CONSTRAINT uk_product_sku_spu_code UNIQUE (spu_id, sku_code),
    CONSTRAINT fk_product_sku_spu FOREIGN KEY (spu_id) REFERENCES product_spu (id),
    CONSTRAINT chk_product_sku_original_price CHECK (original_price > 0),
    INDEX idx_product_sku_spu_active (spu_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
