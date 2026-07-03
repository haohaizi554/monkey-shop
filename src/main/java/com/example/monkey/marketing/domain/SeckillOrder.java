package com.example.monkey.marketing.domain;

import java.time.LocalDateTime;
import java.util.Objects;

public record SeckillOrder(
        Long id,
        Long activityId,
        Long skuId,
        Long userId,
        Long orderId,
        int quantity,
        String idempotencyKey,
        LocalDateTime createdAt) {

    public SeckillOrder {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(activityId, "activityId is required");
        Objects.requireNonNull(skuId, "skuId is required");
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
