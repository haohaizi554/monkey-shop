package com.example.monkey.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaymentTransitionPolicyTest {

    @Test
    void suspendedPaymentCannotStartAnotherRefund() {
        assertThat(PaymentTransitionPolicy.allowsRefund(PaymentStatus.SUSPENDED)).isFalse();
        assertThat(PaymentTransitionPolicy.nextStatus(PaymentStatus.SUSPENDED, PaymentEvent.REFUND_PARTIAL))
                .isEmpty();
        assertThat(PaymentTransitionPolicy.nextStatus(PaymentStatus.SUSPENDED, PaymentEvent.REFUND_ALL))
                .isEmpty();
    }

    @Test
    void paidPaymentCanBeRefundedPartiallyOrFully() {
        assertThat(PaymentTransitionPolicy.allowsRefund(PaymentStatus.PAID)).isTrue();
        assertThat(PaymentTransitionPolicy.nextStatus(PaymentStatus.PAID, PaymentEvent.REFUND_PARTIAL))
                .contains(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(PaymentTransitionPolicy.nextStatus(PaymentStatus.PAID, PaymentEvent.REFUND_ALL))
                .contains(PaymentStatus.REFUNDED);
    }

    @Test
    void partiallyRefundedPaymentCanFinishRemainingRefund() {
        assertThat(PaymentTransitionPolicy.allowsRefund(PaymentStatus.PARTIALLY_REFUNDED)).isTrue();
        assertThat(PaymentTransitionPolicy.nextStatus(
                        PaymentStatus.PARTIALLY_REFUNDED, PaymentEvent.REFUND_PARTIAL))
                .contains(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(PaymentTransitionPolicy.nextStatus(PaymentStatus.PARTIALLY_REFUNDED, PaymentEvent.REFUND_ALL))
                .contains(PaymentStatus.REFUNDED);
    }
}
