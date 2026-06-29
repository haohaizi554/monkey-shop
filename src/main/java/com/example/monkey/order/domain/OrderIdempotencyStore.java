package com.example.monkey.order.domain;

import java.time.LocalDateTime;
import java.util.Optional;

public interface OrderIdempotencyStore {

    boolean reserve(Long userId, String idempotencyKey, String requestHash, LocalDateTime expiresAt);

    Optional<IdempotencyReservationRecord> find(Long userId, String idempotencyKey);

    void complete(Long userId, String idempotencyKey, Long orderId);

    record IdempotencyReservationRecord(
            Long id,
            Long userId,
            String idempotencyKey,
            String requestHash,
            Long orderId,
            String status,
            LocalDateTime createdAt,
            LocalDateTime expiresAt) {

        public static final String STATUS_PROCESSING = "PROCESSING";
        public static final String STATUS_COMPLETED = "COMPLETED";

        public boolean isCompleted() {
            return STATUS_COMPLETED.equals(status);
        }
    }
}
