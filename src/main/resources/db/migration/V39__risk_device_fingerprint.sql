CREATE TABLE risk_device_fingerprint (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    device_fingerprint_hash CHAR(64) NOT NULL,
    client_ip VARCHAR(64),
    phone_hmac CHAR(64),
    first_seen_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_risk_device_fingerprint_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    KEY idx_risk_device_users (device_fingerprint_hash, user_id, last_seen_at),
    KEY idx_risk_device_phone_hmac (device_fingerprint_hash, phone_hmac, last_seen_at),
    KEY idx_risk_device_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
