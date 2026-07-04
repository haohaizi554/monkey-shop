package com.example.monkey.risk.domain;

import java.time.LocalDateTime;

public record RiskReviewCase(
        Long id,
        Long userId,
        Long orderId,
        Long productId,
        RiskSignalType type,
        int score,
        RiskReviewStatus status,
        String detail,
        LocalDateTime createdAt,
        LocalDateTime handledAt,
        Long handlerUserId,
        String resolution) {}
