package com.example.monkey.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.monkey.payment.domain.PaymentEvent;
import com.example.monkey.payment.domain.PaymentStatus;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class SpringStateMachinePaymentTransitionResolverTest {

    private final SpringStateMachinePaymentTransitionResolver transitionResolver =
            new SpringStateMachinePaymentTransitionResolver();

    @Test
    void configuredGraphAllowsPaymentRefundAndSuspendFlow() {
        assertThat(transitionResolver.nextStatus(PaymentStatus.PENDING, PaymentEvent.CONFIRM))
                .isEqualTo(PaymentStatus.PAID);
        assertThat(transitionResolver.nextStatus(PaymentStatus.PAID, PaymentEvent.REFUND_PARTIAL))
                .isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(transitionResolver.nextStatus(PaymentStatus.PARTIALLY_REFUNDED, PaymentEvent.REFUND_PARTIAL))
                .isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(transitionResolver.nextStatus(PaymentStatus.PARTIALLY_REFUNDED, PaymentEvent.REFUND_ALL))
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(transitionResolver.nextStatus(PaymentStatus.PAID, PaymentEvent.SUSPEND))
                .isEqualTo(PaymentStatus.SUSPENDED);
    }

    @Test
    void configuredGraphRejectsIllegalTransition() {
        assertThatThrownBy(() -> transitionResolver.nextStatus(PaymentStatus.FAILED, PaymentEvent.REFUND_PARTIAL))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
}
