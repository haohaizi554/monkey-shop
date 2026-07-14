package com.example.monkey.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PaymentOperationAttemptTest {

    private static final LocalDateTime NOW = LocalDateTime.parse("2026-07-04T08:30:00");
    private static final Duration LEASE = Duration.ofMinutes(2);

    @Test
    void lifecycleTracksAttemptsLeaseFailureAndTerminalState() {
        PaymentOperationAttempt initial = PaymentOperationAttempt.initial(NOW, LEASE);

        assertThat(initial.state()).isEqualTo(PaymentOperationState.RESERVED);
        assertThat(initial.attemptCount()).isEqualTo(1);
        assertThat(initial.leaseExpiresAt()).isEqualTo(NOW.plus(LEASE));
        assertThat(initial.lastFailure()).isEqualTo(PaymentFailureClassification.NONE);

        PaymentOperationAttempt retryable =
                initial.retryable(PaymentFailureClassification.TIMEOUT_UNKNOWN, NOW.plus(LEASE));
        PaymentOperationAttempt claimed = retryable.claim(NOW.plus(LEASE), LEASE);

        assertThat(claimed.state()).isEqualTo(PaymentOperationState.RESERVED);
        assertThat(claimed.attemptCount()).isEqualTo(2);
        assertThat(claimed.leaseExpiresAt()).isEqualTo(NOW.plus(LEASE.multipliedBy(2)));
        assertThat(claimed.lastFailure()).isEqualTo(PaymentFailureClassification.TIMEOUT_UNKNOWN);

        assertThat(claimed.completed()).satisfies(completed -> {
            assertThat(completed.state()).isEqualTo(PaymentOperationState.COMPLETED);
            assertThat(completed.leaseExpiresAt()).isNull();
            assertThat(completed.lastFailure()).isEqualTo(PaymentFailureClassification.TIMEOUT_UNKNOWN);
        });
        assertThat(claimed.terminal(PaymentFailureClassification.PROVIDER_REJECTED))
                .satisfies(terminal -> {
                    assertThat(terminal.state()).isEqualTo(PaymentOperationState.TERMINAL_FAILED);
                    assertThat(terminal.leaseExpiresAt()).isNull();
                    assertThat(terminal.lastFailure()).isEqualTo(PaymentFailureClassification.PROVIDER_REJECTED);
                });
    }
}
