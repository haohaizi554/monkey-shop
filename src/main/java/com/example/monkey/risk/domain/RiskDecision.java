package com.example.monkey.risk.domain;

public enum RiskDecision {
    ALLOW,
    RATE_LIMIT,
    TOTP_REQUIRED,
    REVIEW,
    BLOCK
}
