package com.example.monkey.payment.domain;

import java.util.Objects;

public final class PaymentGatewayException extends RuntimeException {

    private final PaymentFailureClassification classification;

    public PaymentGatewayException(PaymentFailureClassification classification, String message) {
        super(message);
        this.classification = Objects.requireNonNull(classification, "classification");
    }

    public static PaymentGatewayException rejected(String message) {
        return new PaymentGatewayException(PaymentFailureClassification.PROVIDER_REJECTED, message);
    }

    public static PaymentGatewayException timeout(String message) {
        return new PaymentGatewayException(PaymentFailureClassification.TIMEOUT_UNKNOWN, message);
    }

    public PaymentFailureClassification classification() {
        return classification;
    }

    public boolean isTerminal() {
        return PaymentFailureClassification.PROVIDER_REJECTED.equals(classification);
    }
}
