package com.example.monkey.tracking.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@FunctionalInterface
public interface AuthoritativeTrackingPort {

    void recordOrderCreated(Long userId, Long orderId, Long productId, BigDecimal amount, LocalDateTime occurredAt);

    /**
     * Records a payment transition that was accepted by the payment workflow.
     *
     * <p>Order-only compatibility implementations fail closed rather than silently dropping this authoritative
     * event. Spring production wiring supplies {@code TrackingApplicationService}, which overrides this operation.
     */
    default void recordPaymentSuccess(Long userId, Long orderId, BigDecimal paidAmount, LocalDateTime paidAt) {
        throw new IllegalStateException("Authoritative payment tracking is not configured");
    }
}
