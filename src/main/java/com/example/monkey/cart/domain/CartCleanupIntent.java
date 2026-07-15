package com.example.monkey.cart.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

public record CartCleanupIntent(
        Long checkoutId,
        Long userId,
        List<Long> skuIds,
        long cartTtlSeconds,
        CartCleanupIntentStatus status,
        int attemptCount,
        LocalDateTime nextAttemptAt,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt) {

    public CartCleanupIntent {
        skuIds =
                skuIds == null ? List.of() : skuIds.stream().distinct().sorted().toList();
        if (checkoutId == null || userId == null || skuIds.isEmpty()) {
            throw new IllegalArgumentException("Cart cleanup intent is incomplete");
        }
    }

    public static CartCleanupIntent pending(
            Long checkoutId, Long userId, List<Long> skuIds, Duration cartTtl, LocalDateTime now) {
        return new CartCleanupIntent(
                checkoutId,
                userId,
                skuIds,
                cartTtl.toSeconds(),
                CartCleanupIntentStatus.PENDING,
                0,
                now,
                null,
                now,
                now,
                null);
    }

    public Duration cartTtl() {
        return Duration.ofSeconds(cartTtlSeconds);
    }

    public CartCleanupIntent completed(LocalDateTime now) {
        return new CartCleanupIntent(
                checkoutId,
                userId,
                skuIds,
                cartTtlSeconds,
                CartCleanupIntentStatus.COMPLETED,
                attemptCount,
                nextAttemptAt,
                null,
                createdAt,
                now,
                now);
    }

    public CartCleanupIntent failed(LocalDateTime now, Duration retryDelay, String error) {
        return new CartCleanupIntent(
                checkoutId,
                userId,
                skuIds,
                cartTtlSeconds,
                CartCleanupIntentStatus.PENDING,
                attemptCount + 1,
                now.plus(retryDelay),
                error,
                createdAt,
                now,
                null);
    }
}
