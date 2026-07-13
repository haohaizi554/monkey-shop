package com.example.monkey.marketing.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SeckillRequestDto(
        @NotNull Long activityId,
        Long userId,
        Long orderId,
        @Min(1) int quantity,
        @NotBlank String idempotencyKey,
        String turnstileToken) {

    public SeckillRequestDto withUserId(Long newUserId) {
        return new SeckillRequestDto(activityId, newUserId, orderId, quantity, idempotencyKey, turnstileToken);
    }
}
