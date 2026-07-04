CREATE TABLE tracking_event (
    id BIGINT NOT NULL,
    user_id BIGINT,
    session_id VARCHAR(96) NOT NULL,
    trace_id VARCHAR(96),
    event_type VARCHAR(64) NOT NULL,
    page VARCHAR(128),
    source VARCHAR(64),
    product_id BIGINT,
    category_id BIGINT,
    order_id BIGINT,
    amount DECIMAL(12, 2),
    attributes_json JSON,
    occurred_at DATETIME(6) NOT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_tracking_event_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT fk_tracking_event_product FOREIGN KEY (product_id) REFERENCES product_spu (id),
    CONSTRAINT fk_tracking_event_order FOREIGN KEY (order_id) REFERENCES orders (id),
    KEY idx_tracking_event_user_time (user_id, occurred_at),
    KEY idx_tracking_event_type_time (event_type, occurred_at),
    KEY idx_tracking_event_session_trace (session_id, trace_id),
    KEY idx_tracking_event_product_time (product_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO permissions (name, description)
SELECT p.name, p.description
FROM (
    SELECT 'TRACKING_READ' AS name, 'Read own tracking profile' AS description
    UNION ALL SELECT 'TRACKING_WRITE', 'Submit tracking events'
    UNION ALL SELECT 'TRACKING_ADMIN', 'Operate tracking dashboards and profile analytics'
) p
WHERE NOT EXISTS (SELECT 1 FROM permissions existing WHERE existing.name = p.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN ('TRACKING_READ', 'TRACKING_WRITE')
WHERE r.name = 'USER'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN ('TRACKING_READ', 'TRACKING_WRITE', 'TRACKING_ADMIN')
WHERE r.name = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
