package com.example.monkey.risk.application.dto;

import com.example.monkey.risk.domain.RiskDecision;
import java.time.LocalDateTime;
import java.util.List;

public record RiskAssessmentResponseDto(
        Long userId,
        int score,
        RiskDecision decision,
        List<RiskSignalDto> signals,
        Long reviewCaseId,
        boolean productAutoUnlisted,
        boolean userTokensRevoked,
        LocalDateTime assessedAt) {}
