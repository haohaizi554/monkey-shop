CREATE TABLE risk_audit_queue (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    order_id BIGINT,
    product_id BIGINT,
    type VARCHAR(64) NOT NULL,
    score INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    detail VARCHAR(512),
    created_at DATETIME(6) NOT NULL,
    handled_at DATETIME(6),
    handler_user_id BIGINT,
    resolution VARCHAR(255),
    PRIMARY KEY (id),
    CONSTRAINT fk_risk_audit_queue_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT fk_risk_audit_queue_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_risk_audit_queue_product FOREIGN KEY (product_id) REFERENCES product_spu (id),
    CONSTRAINT fk_risk_audit_queue_handler FOREIGN KEY (handler_user_id) REFERENCES `user` (id),
    KEY idx_risk_audit_queue_status_created (status, created_at),
    KEY idx_risk_audit_queue_user_created (user_id, created_at),
    KEY idx_risk_audit_queue_product_created (product_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO permissions (name, description)
SELECT p.name, p.description
FROM (
    SELECT 'RISK_READ' AS name, 'Read own risk score and review state' AS description
    UNION ALL SELECT 'RISK_WRITE', 'Submit risk assessment signals'
    UNION ALL SELECT 'RISK_REVIEW', 'Operate risk review queue and blocking decisions'
) p
WHERE NOT EXISTS (SELECT 1 FROM permissions existing WHERE existing.name = p.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN ('RISK_READ', 'RISK_WRITE')
WHERE r.name = 'USER'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN ('RISK_READ', 'RISK_WRITE', 'RISK_REVIEW')
WHERE r.name = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
