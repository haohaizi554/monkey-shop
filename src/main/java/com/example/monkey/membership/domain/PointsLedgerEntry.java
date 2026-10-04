package com.example.monkey.membership.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PointsLedgerEntry(
        Long id,
        Long userId,
        PointsLedgerType type,
        long points,
        BigDecimal moneyEquivalent,
        Long orderId,
        String referenceKey,
        String idempotencyKey,
        String mutationFingerprint,
        LocalDateTime createdAt) {

    /** Compatibility constructor for legacy ledger rows created before intent fingerprints. */
    public PointsLedgerEntry(
            Long id,
            Long userId,
            PointsLedgerType type,
            long points,
            BigDecimal moneyEquivalent,
            Long orderId,
            String referenceKey,
            String idempotencyKey,
            LocalDateTime createdAt) {
        this(id, userId, type, points, moneyEquivalent, orderId, referenceKey, idempotencyKey, null, createdAt);
    }
}
