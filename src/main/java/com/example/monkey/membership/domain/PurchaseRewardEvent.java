package com.example.monkey.membership.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/** Immutable idempotency evidence for each payment success/refund reward event. */
public record PurchaseRewardEvent(
        Long id,
        Long rewardFactId,
        Long paymentId,
        Long orderId,
        Long userId,
        PurchaseRewardEventType type,
        String eventKey,
        String fingerprint,
        BigDecimal refundAmount,
        BigDecimal cumulativeRefundedAmount,
        long targetReversedPoints,
        long appliedPoints,
        LocalDateTime createdAt) {

    public PurchaseRewardEvent {
        refundAmount = money(refundAmount);
        cumulativeRefundedAmount = money(cumulativeRefundedAmount);
        if (rewardFactId == null || rewardFactId <= 0 || paymentId == null || paymentId <= 0
                || orderId == null || orderId <= 0 || userId == null || userId <= 0) {
            throw new IllegalArgumentException("purchase reward event ownership ids must be positive");
        }
        if (type == null || eventKey == null || eventKey.isBlank() || fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("purchase reward event identity is required");
        }
        if (targetReversedPoints < 0 || appliedPoints < 0 || appliedPoints > targetReversedPoints) {
            throw new IllegalArgumentException("purchase reward event points are invalid");
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
