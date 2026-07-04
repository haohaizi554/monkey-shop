CREATE TABLE order_fulfillment_item (
    id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    product_name VARCHAR(191) NOT NULL,
    ordered_quantity INT NOT NULL,
    shipped_quantity INT NOT NULL DEFAULT 0,
    received_quantity INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_order_fulfillment_item_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT uk_order_fulfillment_item_order_sku UNIQUE (order_id, sku_id),
    CONSTRAINT ck_order_fulfillment_item_quantity CHECK (
        ordered_quantity > 0
        AND shipped_quantity >= 0
        AND received_quantity >= 0
        AND shipped_quantity <= ordered_quantity
        AND received_quantity <= shipped_quantity
    ),
    KEY idx_order_fulfillment_item_order (order_id),
    KEY idx_order_fulfillment_item_sku (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE order_shipment_batch (
    id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    shipment_no VARCHAR(64) NOT NULL,
    carrier VARCHAR(64) NOT NULL,
    tracking_no VARCHAR(96) NOT NULL,
    status VARCHAR(32) NOT NULL,
    shipped_at DATETIME(6) NOT NULL,
    received_at DATETIME(6),
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_order_shipment_batch_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT uk_order_shipment_batch_no UNIQUE (shipment_no),
    CONSTRAINT uk_order_shipment_tracking UNIQUE (carrier, tracking_no),
    KEY idx_order_shipment_batch_order (order_id),
    KEY idx_order_shipment_batch_status_time (status, shipped_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE order_shipment_line (
    id BIGINT NOT NULL,
    shipment_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    product_name VARCHAR(191) NOT NULL,
    quantity INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_order_shipment_line_batch FOREIGN KEY (shipment_id) REFERENCES order_shipment_batch (id),
    CONSTRAINT fk_order_shipment_line_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_order_shipment_line_quantity CHECK (quantity > 0),
    KEY idx_order_shipment_line_shipment (shipment_id),
    KEY idx_order_shipment_line_order_sku (order_id, sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
