CREATE TABLE product_category (
    id BIGINT NOT NULL,
    parent_id BIGINT NULL,
    level INT NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    active BIT(1) NOT NULL DEFAULT b'1',
    PRIMARY KEY (id),
    CONSTRAINT uk_product_category_parent_code UNIQUE (parent_id, code),
    CONSTRAINT fk_product_category_parent FOREIGN KEY (parent_id) REFERENCES product_category (id),
    CONSTRAINT chk_product_category_level CHECK (level BETWEEN 1 AND 3),
    INDEX idx_product_category_parent_level (parent_id, level, sort_order),
    INDEX idx_product_category_active_level (active, level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO product_category (id, parent_id, level, code, name, sort_order, active) VALUES
    (100000000001, NULL, 1, 'digital', '数码', 10, b'1'),
    (100000000002, 100000000001, 2, 'phone', '手机', 10, b'1'),
    (100000000003, 100000000002, 3, 'smartphone', '智能手机', 10, b'1'),
    (100000000004, 100000000001, 2, 'computer', '电脑', 20, b'1'),
    (100000000005, 100000000004, 3, 'laptop', '笔记本电脑', 10, b'1'),
    (100000000006, NULL, 1, 'home-appliance', '家用电器', 20, b'1'),
    (100000000007, 100000000006, 2, 'kitchen', '厨房电器', 10, b'1'),
    (100000000008, 100000000007, 3, 'rice-cooker', '电饭煲', 10, b'1');

ALTER TABLE product_spu
    ADD CONSTRAINT fk_product_spu_category FOREIGN KEY (category_id) REFERENCES product_category (id);
