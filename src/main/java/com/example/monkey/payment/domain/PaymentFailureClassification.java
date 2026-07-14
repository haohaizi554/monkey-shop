package com.example.monkey.payment.domain;

public enum PaymentFailureClassification {
    NONE,
    TIMEOUT_UNKNOWN,
    UNKNOWN,
    LOCAL_COMPLETION,
    PROVIDER_REJECTED,
    LEGACY_UNKNOWN
}
