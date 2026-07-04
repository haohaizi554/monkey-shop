package com.example.monkey.payment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PaymentReconciliationReport(
        Long id,
        PaymentMethod provider,
        LocalDate reportDate,
        BigDecimal platformAmount,
        BigDecimal providerAmount,
        BigDecimal diffAmount,
        int issueCount,
        ReconciliationStatus status,
        String reportPayload,
        LocalDateTime createTime) {

    public PaymentReconciliationReport {
        platformAmount = money(platformAmount);
        providerAmount = money(providerAmount);
        diffAmount = money(diffAmount);
        if (issueCount < 0) {
            throw new IllegalArgumentException("issue count must not be negative");
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
