package com.example.monkey.membership.application.dto;

import com.example.monkey.membership.domain.PointsLedgerType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PointsLedgerEntryDto(
        Long id,
        PointsLedgerType type,
        long points,
        BigDecimal moneyEquivalent,
        Long orderId,
        String referenceKey,
        LocalDateTime createdAt) {}
