package com.example.monkey.payment.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

public record PaymentQueryAttempt(int attemptToken, LocalDateTime leaseExpiresAt, LocalDateTime nextReadyAt) {

    public PaymentQueryAttempt {
        if (attemptToken < 0) {
            throw new IllegalArgumentException("attemptToken must not be negative");
        }
        if (leaseExpiresAt != null && attemptToken == 0) {
            throw new IllegalArgumentException("a query lease requires a positive attempt token");
        }
        if (leaseExpiresAt != null && nextReadyAt == null) {
            throw new IllegalArgumentException("a query lease requires a next-ready timestamp");
        }
    }

    public static PaymentQueryAttempt notScheduled() {
        return new PaymentQueryAttempt(0, null, null);
    }

    public static PaymentQueryAttempt readyAt(LocalDateTime nextReadyAt) {
        return new PaymentQueryAttempt(0, null, Objects.requireNonNull(nextReadyAt, "nextReadyAt"));
    }

    public boolean isClaimableAt(LocalDateTime now) {
        LocalDateTime claimTime = Objects.requireNonNull(now, "now");
        return nextReadyAt != null
                && !nextReadyAt.isAfter(claimTime)
                && (leaseExpiresAt == null || !leaseExpiresAt.isAfter(claimTime));
    }

    public PaymentQueryAttempt claim(LocalDateTime now, Duration leaseDuration) {
        LocalDateTime claimTime = Objects.requireNonNull(now, "now");
        Duration lease = requirePositive(leaseDuration);
        if (!isClaimableAt(claimTime)) {
            throw new IllegalStateException("payment query lease is active or the query is not ready");
        }
        return new PaymentQueryAttempt(attemptToken + 1, claimTime.plus(lease), nextReadyAt);
    }

    public PaymentQueryAttempt retryAt(LocalDateTime retryAt) {
        if (attemptToken == 0) {
            throw new IllegalStateException("an unclaimed payment query cannot be retried");
        }
        return new PaymentQueryAttempt(attemptToken, null, Objects.requireNonNull(retryAt, "retryAt"));
    }

    public PaymentQueryAttempt stop() {
        return new PaymentQueryAttempt(attemptToken, null, null);
    }

    private static Duration requirePositive(Duration duration) {
        Duration required = Objects.requireNonNull(duration, "leaseDuration");
        if (required.isZero() || required.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        return required;
    }
}
