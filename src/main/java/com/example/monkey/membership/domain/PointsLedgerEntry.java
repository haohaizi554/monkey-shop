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
        LocalDateTime createdAt) {}
