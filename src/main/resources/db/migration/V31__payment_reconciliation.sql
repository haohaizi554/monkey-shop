CREATE TABLE payment_reconciliation_report (
    id BIGINT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    report_date DATE NOT NULL,
    platform_amount DECIMAL(12, 2) NOT NULL,
    provider_amount DECIMAL(12, 2) NOT NULL,
    diff_amount DECIMAL(12, 2) NOT NULL,
    issue_count INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    encrypted_report_payload VARCHAR(2048) NOT NULL,
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_reconciliation_provider_date UNIQUE (provider, report_date),
    CONSTRAINT ck_payment_reconciliation_issue_count CHECK (issue_count >= 0),
    KEY idx_payment_reconciliation_date_status (report_date, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
