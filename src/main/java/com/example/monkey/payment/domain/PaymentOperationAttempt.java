package com.example.monkey.payment.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

public record PaymentOperationAttempt(
        PaymentOperationState state,
        int attemptCount,
        LocalDateTime leaseExpiresAt,
        PaymentFailureClassification lastFailure) {

    public PaymentOperationAttempt {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(lastFailure, "lastFailure");
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must not be negative");
        }
        if (isRecoverable(state) && (attemptCount == 0 || leaseExpiresAt == null)) {
            throw new IllegalArgumentException("recoverable payment operations require an attempt and lease");
        }
        if (!isRecoverable(state) && leaseExpiresAt != null) {
            throw new IllegalArgumentException("terminal payment operations must not retain a lease");
        }
    }

    public static PaymentOperationAttempt initial(LocalDateTime now, Duration leaseDuration) {
        return new PaymentOperationAttempt(
                PaymentOperationState.RESERVED,
                1,
                requireNow(now).plus(requireLease(leaseDuration)),
                PaymentFailureClassification.NONE);
    }

    public static PaymentOperationAttempt legacy() {
        return new PaymentOperationAttempt(
                PaymentOperationState.LEGACY_UNREPLAYABLE, 0, null, PaymentFailureClassification.LEGACY_UNKNOWN);
    }

    public PaymentOperationAttempt retryable(PaymentFailureClassification failure, LocalDateTime retryAfter) {
        return new PaymentOperationAttempt(
                PaymentOperationState.RETRYABLE,
                attemptCount,
                Objects.requireNonNull(retryAfter, "retryAfter"),
                Objects.requireNonNull(failure, "failure"));
    }

    public PaymentOperationAttempt claim(LocalDateTime now, Duration leaseDuration) {
        if (!isRecoverable()) {
            throw new IllegalStateException("only recoverable payment operations can be claimed");
        }
        return new PaymentOperationAttempt(
                PaymentOperationState.RESERVED,
                attemptCount + 1,
                requireNow(now).plus(requireLease(leaseDuration)),
                lastFailure);
    }

    public PaymentOperationAttempt completed() {
        return new PaymentOperationAttempt(PaymentOperationState.COMPLETED, attemptCount, null, lastFailure);
    }

    public PaymentOperationAttempt terminal(PaymentFailureClassification failure) {
        return new PaymentOperationAttempt(
                PaymentOperationState.TERMINAL_FAILED, attemptCount, null, Objects.requireNonNull(failure, "failure"));
    }

    public boolean isRecoverable() {
        return isRecoverable(state);
    }

    public boolean isExpired(LocalDateTime now) {
        return isRecoverable() && !leaseExpiresAt.isAfter(requireNow(now));
    }

    private static boolean isRecoverable(PaymentOperationState state) {
        return PaymentOperationState.RESERVED.equals(state) || PaymentOperationState.RETRYABLE.equals(state);
    }

    private static LocalDateTime requireNow(LocalDateTime now) {
        return Objects.requireNonNull(now, "now");
    }

    private static Duration requireLease(Duration leaseDuration) {
        Duration lease = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        return lease;
    }
}
