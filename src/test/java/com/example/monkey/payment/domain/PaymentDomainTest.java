package com.example.monkey.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PaymentDomainTest {

    @Test
    void transitionPolicyAllowsPayRefundAndSuspendFlow() {
        assertThat(PaymentTransitionPolicy.nextStatus(PaymentStatus.PENDING, PaymentEvent.CONFIRM))
                .contains(PaymentStatus.PAID);
        assertThat(PaymentTransitionPolicy.nextStatus(PaymentStatus.PAID, PaymentEvent.REFUND_PARTIAL))
                .contains(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(PaymentTransitionPolicy.nextStatus(PaymentStatus.PARTIALLY_REFUNDED, PaymentEvent.REFUND_ALL))
                .contains(PaymentStatus.REFUNDED);
        assertThat(PaymentTransitionPolicy.nextStatus(PaymentStatus.PAID, PaymentEvent.SUSPEND))
                .contains(PaymentStatus.SUSPENDED);
    }

    @Test
    void paymentOrderKeepsRefundInvariant() {
        PaymentOrder paid = pendingPayment().markPaid("wx-trade-1", LocalDateTime.parse("2026-07-04T08:10:00"));

        PaymentOrder partiallyRefunded = paid.refund(
                new BigDecimal("30.00"), PaymentStatus.PARTIALLY_REFUNDED, LocalDateTime.parse("2026-07-04T08:20:00"));

        assertThat(partiallyRefunded.refundableAmount()).isEqualByComparingTo(new BigDecimal("70.00"));
        assertThatThrownBy(() -> paid.refund(
                        new BigDecimal("130.00"), PaymentStatus.REFUNDED, LocalDateTime.parse("2026-07-04T08:30:00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("refunded amount must not exceed paid amount");
    }

    private static PaymentOrder pendingPayment() {
        return new PaymentOrder(
                100L,
                "PAY100",
                10L,
                42L,
                PaymentMethod.WECHAT,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                PaymentStatus.PENDING,
                "pay-key",
                null,
                null,
                null,
                null,
                null,
                LocalDateTime.parse("2026-07-04T08:00:00"),
                LocalDateTime.parse("2026-07-04T08:00:00"));
    }
}
