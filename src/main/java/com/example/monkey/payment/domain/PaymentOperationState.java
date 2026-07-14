package com.example.monkey.payment.domain;

public enum PaymentOperationState {
    RESERVED,
    RETRYABLE,
    COMPLETED,
    TERMINAL_FAILED,
    LEGACY_UNREPLAYABLE
}
