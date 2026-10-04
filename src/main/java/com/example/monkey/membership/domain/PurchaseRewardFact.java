package com.example.monkey.membership.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/** Durable server-owned facts for the points awarded by one successful payment. */
public record PurchaseRewardFact(
        Long id,
        Long paymentId,
        Long orderId,
        Long userId,
        BigDecimal originalPaidAmount,
        String providerTradeNo,
        int pointsMultiplier,
        long awardedPoints,
        long reversedPoints,
        BigDecimal cumulativeRefundedAmount,
        String awardEventKey,
        String awardFingerprint,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public PurchaseRewardFact {
        originalPaidAmount = money(originalPaidAmount);
        cumulativeRefundedAmount = money(cumulativeRefundedAmount);
        if (paymentId == null || paymentId <= 0 || orderId == null || orderId <= 0 || userId == null || userId <= 0) {
            throw new IllegalArgumentException("purchase reward ownership ids must be positive");
        }
        if (originalPaidAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("purchase reward amount must be positive");
        }
        if (pointsMultiplier <= 0 || awardedPoints <= 0 || reversedPoints < 0 || reversedPoints > awardedPoints) {
            throw new IllegalArgumentException("purchase reward points are invalid");
        }
        if (cumulativeRefundedAmount.compareTo(originalPaidAmount) > 0) {
            throw new IllegalArgumentException("purchase reward refund exceeds original payment");
        }
    }

    public PurchaseRewardFact withRefundProgress(
            long nextReversedPoints, BigDecimal nextCumulativeRefundedAmount, LocalDateTime now) {
        return new PurchaseRewardFact(
                id,
                paymentId,
                orderId,
                userId,
                originalPaidAmount,
                providerTradeNo,
                pointsMultiplier,
                awardedPoints,
                nextReversedPoints,
                nextCumulativeRefundedAmount,
                awardEventKey,
                awardFingerprint,
                version,
                createdAt,
                now);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
