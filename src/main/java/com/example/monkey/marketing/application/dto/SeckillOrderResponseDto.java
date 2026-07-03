package com.example.monkey.marketing.application.dto;

import java.time.LocalDateTime;

public record SeckillOrderResponseDto(
        Long id,
        Long activityId,
        Long skuId,
        Long userId,
        Long orderId,
        int quantity,
        String idempotencyKey,
        LocalDateTime createdAt) {}
