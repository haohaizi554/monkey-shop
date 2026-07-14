package com.example.monkey.payment.domain;

import java.util.Objects;
import java.util.Set;

final class PaymentTerminalFailureCodes {

    static final String GENERIC_PROVIDER_REJECTION = "PROVIDER_REJECTED";

    private static final Set<String> ALLOWED = Set.of(GENERIC_PROVIDER_REJECTION, "CARD_DECLINED", "REFUND_DECLINED");

    private PaymentTerminalFailureCodes() {}

    static String sanitize(String candidate) {
        return ALLOWED.contains(candidate) ? candidate : GENERIC_PROVIDER_REJECTION;
    }

    static String requireWhitelisted(String code) {
        String required = Objects.requireNonNull(code, "terminalFailureCode");
        if (!ALLOWED.contains(required)) {
            throw new IllegalArgumentException("terminalFailureCode is not an allowed public provider code");
        }
        return required;
    }
}
