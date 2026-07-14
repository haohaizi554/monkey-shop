package com.example.monkey.payment.domain;

import java.math.BigDecimal;

public record RefundResponseSnapshot(
        BigDecimal refundedAmount, PaymentStatus paymentStatus, PaymentLedgerStatus ledgerStatus) {

    public static RefundResponseSnapshot capture(PaymentOrder payment, PaymentLedgerEntry ledger) {
        return new RefundResponseSnapshot(payment.refundedAmount(), payment.status(), ledger.status());
    }
}
