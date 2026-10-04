package com.example.monkey.marketing.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Durable decision binding for one tenant/user/idempotency-key group-buy request.
 *
 * <p>The binding is the correctness authority. Redis may cache the same decision, but it is never
 * required to replay or validate a request.
 */
public record GroupBuyIdempotencyBinding(
        Long tenantId,
        Long userId,
        String idempotencyKey,
        Long teamId,
        String requestFingerprint,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public GroupBuyIdempotencyBinding {
        requirePositive(tenantId, "tenant id");
        requirePositive(userId, "user id");
        requirePositive(teamId, "team id");
        idempotencyKey = requireText(idempotencyKey, "idempotency key");
        requestFingerprint = requireText(requestFingerprint, "request fingerprint");
        createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
