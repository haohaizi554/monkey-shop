CREATE TABLE tenant_billing_account (
    tenant_id BIGINT NOT NULL,
    plan VARCHAR(32) NOT NULL,
    monthly_fee DECIMAL(12, 2) NOT NULL,
    included_order_count BIGINT NOT NULL,
    extra_order_unit_fee DECIMAL(12, 4) NOT NULL,
    balance DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id),
    CONSTRAINT fk_tenant_billing_account_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    KEY idx_tenant_billing_account_plan (plan)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tenant_bill (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    billing_month CHAR(7) NOT NULL,
    plan VARCHAR(32) NOT NULL,
    order_count BIGINT NOT NULL,
    monthly_fee DECIMAL(12, 2) NOT NULL,
    usage_fee DECIMAL(12, 2) NOT NULL,
    total_amount DECIMAL(12, 2) NOT NULL,
    payment_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    status VARCHAR(32) NOT NULL,
    generated_at DATETIME(6) NOT NULL,
    reconciled_at DATETIME(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_tenant_bill_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT uk_tenant_bill_month UNIQUE (tenant_id, billing_month),
    KEY idx_tenant_bill_status_month (status, billing_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tenant_data_export_job (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    export_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    encrypted_archive_path VARCHAR(512),
    requested_by BIGINT NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    audit_trace_id VARCHAR(128),
    error_message VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_tenant_data_export_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_tenant_data_export_requester FOREIGN KEY (requested_by) REFERENCES `user` (id),
    KEY idx_tenant_export_status_requested (status, requested_at),
    KEY idx_tenant_export_tenant_requested (tenant_id, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tenant_billing_reconciliation (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    bill_id BIGINT NOT NULL,
    platform_order_count BIGINT NOT NULL,
    billed_order_count BIGINT NOT NULL,
    platform_amount DECIMAL(12, 2) NOT NULL,
    billed_amount DECIMAL(12, 2) NOT NULL,
    diff_amount DECIMAL(12, 2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reconciled_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_tenant_billing_reconciliation_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_tenant_billing_reconciliation_bill FOREIGN KEY (bill_id) REFERENCES tenant_bill (id),
    KEY idx_tenant_billing_reconciliation_status (tenant_id, status, reconciled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
