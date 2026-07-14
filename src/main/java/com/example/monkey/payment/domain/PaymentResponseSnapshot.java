package com.example.monkey.payment.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentResponseSnapshot(
        BigDecimal paidAmount,
        BigDecimal refundedAmount,
        PaymentStatus status,
        String providerTradeNo,
        String paymentUrl,
        LocalDateTime paidAt) {

    public static PaymentResponseSnapshot capture(PaymentOrder payment, String paymentUrl) {
        return new PaymentResponseSnapshot(
                payment.paidAmount(),
                payment.refundedAmount(),
                payment.status(),
                payment.providerTradeNo(),
                paymentUrl,
                payment.paidAt());
    }
}
