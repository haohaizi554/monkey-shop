package com.example.monkey.payment.domain;

public enum PaymentEvent {
    CONFIRM,
    FAIL,
    REFUND_PARTIAL,
    REFUND_ALL,
    SUSPEND
}
