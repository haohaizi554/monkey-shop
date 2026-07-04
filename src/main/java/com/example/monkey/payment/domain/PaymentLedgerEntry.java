package com.example.monkey.payment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

public record PaymentLedgerEntry(
        Long id,
        Long paymentId,
        Long orderId,
        Long userId,
        PaymentLedgerType type,
        BigDecimal amount,
        PaymentLedgerStatus status,
        String requestKey,
        String providerTradeNo,
        LocalDateTime createTime) {

    public PaymentLedgerEntry {
        amount = (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("payment ledger amount must be positive");
        }
    }
}
