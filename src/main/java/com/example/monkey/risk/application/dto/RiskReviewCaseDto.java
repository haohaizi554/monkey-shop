package com.example.monkey.risk.application.dto;

import com.example.monkey.risk.domain.RiskReviewStatus;
import com.example.monkey.risk.domain.RiskSignalType;
import java.time.LocalDateTime;

public record RiskReviewCaseDto(
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
