package com.example.monkey.payment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record ReconciliationLine(String paymentNo, String providerTradeNo, BigDecimal amount) {

    public ReconciliationLine {
        amount = (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
    }
}
