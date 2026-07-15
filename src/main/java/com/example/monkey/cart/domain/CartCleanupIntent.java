package com.example.monkey.cart.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

public record CartCleanupIntent(
        Long checkoutId,
        Long userId,
        List<CartItem> itemSnapshots,
        long cartTtlSeconds,
        CartCleanupIntentStatus status,
        int attemptCount,
        LocalDateTime nextAttemptAt,
        String claimToken,
        LocalDateTime leaseExpiresAt,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt) {

    public CartCleanupIntent {
        itemSnapshots = itemSnapshots == null
                ? List.of()
                : itemSnapshots.stream()
                        .sorted(java.util.Comparator.comparing(CartItem::skuId))
                        .toList();
        if (checkoutId == null || userId == null || itemSnapshots.isEmpty()) {
            throw new IllegalArgumentException("Cart cleanup intent is incomplete");
        }
        boolean processing = CartCleanupIntentStatus.PROCESSING.equals(status);
        if (processing != (claimToken != null && leaseExpiresAt != null)) {
            throw new IllegalArgumentException("Cart cleanup claim state is inconsistent");
        }
    }

    public static CartCleanupIntent pending(
            Long checkoutId, Long userId, List<CartItem> itemSnapshots, Duration cartTtl, LocalDateTime now) {
        return new CartCleanupIntent(
                checkoutId,
                userId,
                itemSnapshots,
                cartTtl.toSeconds(),
                CartCleanupIntentStatus.PENDING,
                0,
                now,
                null,
                null,
                null,
                now,
                now,
                null);
    }

    public Duration cartTtl() {
        return Duration.ofSeconds(cartTtlSeconds);
    }
}
