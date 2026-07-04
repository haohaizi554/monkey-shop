package com.example.monkey.payment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

public record PaymentOrder(
        Long id,
        String paymentNo,
        Long orderId,
        Long userId,
        PaymentMethod method,
        BigDecimal amount,
        BigDecimal paidAmount,
        BigDecimal refundedAmount,
        PaymentStatus status,
        String idempotencyKey,
        String providerTradeNo,
        String bankCardNo,
        String bankCardLast4,
        String bankCardBlindIndex,
        LocalDateTime paidAt,
        LocalDateTime createTime,
        LocalDateTime updateTime) {

    public PaymentOrder {
        amount = money(amount);
        paidAmount = money(paidAmount);
        refundedAmount = money(refundedAmount);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("payment amount must be positive");
        }
        if (refundedAmount.compareTo(paidAmount) > 0) {
            throw new IllegalArgumentException("refunded amount must not exceed paid amount");
        }
    }

    public BigDecimal refundableAmount() {
        return paidAmount.subtract(refundedAmount).setScale(2, RoundingMode.HALF_UP);
    }

    public PaymentOrder markPaid(String tradeNo, LocalDateTime paidTime) {
        return new PaymentOrder(
                id,
                paymentNo,
                orderId,
                userId,
                method,
                amount,
                amount,
                refundedAmount,
                PaymentStatus.PAID,
                idempotencyKey,
                hasText(tradeNo) ? tradeNo : providerTradeNo,
                bankCardNo,
                bankCardLast4,
                bankCardBlindIndex,
                paidTime,
                createTime,
                paidTime);
    }

    public PaymentOrder withProviderTradeNo(String tradeNo, LocalDateTime updatedAt) {
        return new PaymentOrder(
                id,
                paymentNo,
                orderId,
                userId,
                method,
                amount,
                paidAmount,
                refundedAmount,
                status,
                idempotencyKey,
                hasText(tradeNo) ? tradeNo : providerTradeNo,
                bankCardNo,
                bankCardLast4,
                bankCardBlindIndex,
                paidAt,
                createTime,
                updatedAt);
    }

    public PaymentOrder refund(BigDecimal refundAmount, PaymentStatus nextStatus, LocalDateTime refundedAt) {
        return new PaymentOrder(
                id,
                paymentNo,
                orderId,
                userId,
                method,
                amount,
                paidAmount,
                refundedAmount.add(money(refundAmount)),
                nextStatus,
                idempotencyKey,
                providerTradeNo,
                bankCardNo,
                bankCardLast4,
                bankCardBlindIndex,
                paidAt,
                createTime,
                refundedAt);
    }

    public PaymentOrder fail(LocalDateTime failedAt) {
        return new PaymentOrder(
                id,
                paymentNo,
                orderId,
                userId,
                method,
                amount,
                paidAmount,
                refundedAmount,
                PaymentStatus.FAILED,
                idempotencyKey,
                providerTradeNo,
                bankCardNo,
                bankCardLast4,
                bankCardBlindIndex,
                paidAt,
                createTime,
                failedAt);
    }

    public PaymentOrder suspend(LocalDateTime suspendedAt) {
        return new PaymentOrder(
                id,
                paymentNo,
                orderId,
                userId,
                method,
                amount,
                paidAmount,
                refundedAmount,
                PaymentStatus.SUSPENDED,
                idempotencyKey,
                providerTradeNo,
                bankCardNo,
                bankCardLast4,
                bankCardBlindIndex,
                paidAt,
                createTime,
                suspendedAt);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
