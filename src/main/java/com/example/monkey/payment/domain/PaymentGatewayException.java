package com.example.monkey.payment.domain;

import java.util.Objects;

public final class PaymentGatewayException extends RuntimeException {

    private static final String TERMINAL_MESSAGE = "Payment provider rejected the operation";

    private final PaymentFailureClassification classification;
    private final String providerCode;

    public PaymentGatewayException(PaymentFailureClassification classification, String message) {
        this(classification, classification.name(), message);
    }

    private PaymentGatewayException(PaymentFailureClassification classification, String providerCode, String message) {
        super(message);
        this.classification = Objects.requireNonNull(classification, "classification");
        this.providerCode = Objects.requireNonNull(providerCode, "providerCode");
    }

    public static PaymentGatewayException rejected(String message) {
        return rejected(PaymentTerminalFailureCodes.GENERIC_PROVIDER_REJECTION, message);
    }

    public static PaymentGatewayException rejected(String providerCode, String ignoredRawMessage) {
        return new PaymentGatewayException(
                PaymentFailureClassification.PROVIDER_REJECTED,
                PaymentTerminalFailureCodes.sanitize(providerCode),
                TERMINAL_MESSAGE);
    }

    public static PaymentGatewayException terminalReplay(String providerCode) {
        return new PaymentGatewayException(
                PaymentFailureClassification.PROVIDER_REJECTED,
                PaymentTerminalFailureCodes.sanitize(providerCode),
                TERMINAL_MESSAGE);
    }

    public static PaymentGatewayException timeout(String message) {
        return new PaymentGatewayException(PaymentFailureClassification.TIMEOUT_UNKNOWN, message);
    }

    public PaymentFailureClassification classification() {
        return classification;
    }

    public String providerCode() {
        return providerCode;
    }

    public boolean isTerminal() {
        return PaymentFailureClassification.PROVIDER_REJECTED.equals(classification);
    }
}
