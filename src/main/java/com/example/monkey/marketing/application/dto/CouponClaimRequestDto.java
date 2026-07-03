package com.example.monkey.marketing.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CouponClaimRequestDto(
        @NotNull Long couponId,
        @NotNull Long userId,
        @NotBlank String idempotencyKey) {}
