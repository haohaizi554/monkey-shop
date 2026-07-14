package com.example.monkey.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PaymentQueryAttemptTest {

    private static final LocalDateTime NOW = LocalDateTime.parse("2026-07-14T08:00:00");

    @Test
    void claimUsesAMonotonicTokenAndFiniteLease() {
        PaymentQueryAttempt ready = PaymentQueryAttempt.readyAt(NOW);

        PaymentQueryAttempt claimed = ready.claim(NOW, Duration.ofMinutes(2));

        assertThat(claimed.attemptToken()).isEqualTo(1);
        assertThat(claimed.leaseExpiresAt()).isEqualTo(NOW.plusMinutes(2));
        assertThat(claimed.nextReadyAt()).isEqualTo(NOW);
        assertThat(claimed.isClaimableAt(NOW.plusMinutes(1))).isFalse();
        assertThatThrownBy(() -> claimed.claim(NOW.plusMinutes(1), Duration.ofMinutes(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease");

        PaymentQueryAttempt reclaimed = claimed.claim(NOW.plusMinutes(2), Duration.ofMinutes(2));
        assertThat(reclaimed.attemptToken()).isEqualTo(2);
        assertThat(reclaimed.leaseExpiresAt()).isEqualTo(NOW.plusMinutes(4));
    }

    @Test
    void pendingAndErrorRetryKeepTheTokenButMoveTheNextReadyTime() {
        PaymentQueryAttempt claimed = PaymentQueryAttempt.readyAt(NOW).claim(NOW, Duration.ofMinutes(2));

        PaymentQueryAttempt retry = claimed.retryAt(NOW.plusSeconds(30));

        assertThat(retry.attemptToken()).isEqualTo(1);
        assertThat(retry.leaseExpiresAt()).isNull();
        assertThat(retry.nextReadyAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(retry.isClaimableAt(NOW.plusSeconds(29))).isFalse();
        assertThat(retry.isClaimableAt(NOW.plusSeconds(30))).isTrue();
    }

    @Test
    void terminalProviderResultStopsFurtherQueries() {
        PaymentQueryAttempt claimed = PaymentQueryAttempt.readyAt(NOW).claim(NOW, Duration.ofMinutes(2));

        PaymentQueryAttempt stopped = claimed.stop();

        assertThat(stopped.attemptToken()).isEqualTo(1);
        assertThat(stopped.leaseExpiresAt()).isNull();
        assertThat(stopped.nextReadyAt()).isNull();
        assertThat(stopped.isClaimableAt(NOW.plusDays(1))).isFalse();
    }
}
