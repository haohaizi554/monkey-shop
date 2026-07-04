CREATE TABLE risk_score (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    device_fingerprint_hash CHAR(64) NOT NULL,
    phone_hmac CHAR(64),
    score INT NOT NULL,
    decision VARCHAR(32) NOT NULL,
    signals_json JSON,
    assessed_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_risk_score_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    KEY idx_risk_score_user_assessed (user_id, assessed_at),
    KEY idx_risk_score_decision_assessed (decision, assessed_at),
    KEY idx_risk_score_expires_at (expires_at),
    KEY idx_risk_score_phone_hmac (phone_hmac)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
