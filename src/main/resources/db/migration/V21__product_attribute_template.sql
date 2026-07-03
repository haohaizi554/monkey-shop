CREATE TABLE product_attribute_template (
    id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    template_code VARCHAR(64) NOT NULL,
    template_name VARCHAR(128) NOT NULL,
    required_attributes_json JSON NULL,
    optional_attributes_json JSON NULL,
    active BIT(1) NOT NULL DEFAULT b'1',
    PRIMARY KEY (id),
    CONSTRAINT uk_product_attribute_template_category_code UNIQUE (category_id, template_code),
    CONSTRAINT fk_product_attribute_template_category FOREIGN KEY (category_id) REFERENCES product_category (id),
    INDEX idx_product_attribute_template_category_active (category_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO product_attribute_template (
    id,
    category_id,
    template_code,
    template_name,
    required_attributes_json,
    optional_attributes_json,
    active
) VALUES
    (
        100000001001,
        100000000003,
        'smartphone-core',
        '智能手机核心属性',
        JSON_ARRAY('brand', 'model', 'memory', 'storage'),
        JSON_ARRAY('screenSize', 'chipset', 'batteryCapacity', 'network'),
        b'1'
    ),
    (
        100000001002,
        100000000005,
        'laptop-core',
        '笔记本电脑核心属性',
        JSON_ARRAY('brand', 'model', 'cpu', 'memory', 'storage'),
        JSON_ARRAY('screenSize', 'graphics', 'weight'),
        b'1'
    ),
    (
        100000001003,
        100000000008,
        'rice-cooker-core',
        '电饭煲核心属性',
        JSON_ARRAY('brand', 'capacity', 'heatingMethod'),
        JSON_ARRAY('linerMaterial', 'energyLevel'),
        b'1'
    );
