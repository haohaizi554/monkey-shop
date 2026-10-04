package com.example.monkey.membership.application.dto;

import com.example.monkey.membership.domain.IdentityVerificationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record IdentityReviewRequestDto(
        @NotNull IdentityVerificationStatus status, @NotBlank String reason, @NotBlank String totpCode) {}
