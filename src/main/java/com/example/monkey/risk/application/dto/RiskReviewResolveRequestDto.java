package com.example.monkey.risk.application.dto;

import com.example.monkey.risk.domain.RiskReviewStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RiskReviewResolveRequestDto(
        @NotNull RiskReviewStatus status,
        @Size(max = 255) String resolution,
        @Size(max = 16) String totpCode) {}
