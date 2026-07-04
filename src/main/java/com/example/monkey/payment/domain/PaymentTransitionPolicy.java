package com.example.monkey.payment.domain;

import java.util.List;
import java.util.Optional;

public final class PaymentTransitionPolicy {

    public static final String STATUS_TRANSITION_NOT_ALLOWED = "Payment status does not allow this operation";

    private static final List<PaymentTransition> TRANSITIONS = List.of(
            new PaymentTransition(PaymentStatus.PENDING, PaymentEvent.CONFIRM, PaymentStatus.PAID),
            new PaymentTransition(PaymentStatus.PENDING, PaymentEvent.FAIL, PaymentStatus.FAILED),
            new PaymentTransition(PaymentStatus.PENDING, PaymentEvent.SUSPEND, PaymentStatus.SUSPENDED),
            new PaymentTransition(PaymentStatus.PAID, PaymentEvent.REFUND_PARTIAL, PaymentStatus.PARTIALLY_REFUNDED),
            new PaymentTransition(PaymentStatus.PAID, PaymentEvent.REFUND_ALL, PaymentStatus.REFUNDED),
            new PaymentTransition(PaymentStatus.PAID, PaymentEvent.SUSPEND, PaymentStatus.SUSPENDED),
            new PaymentTransition(
                    PaymentStatus.PARTIALLY_REFUNDED, PaymentEvent.REFUND_PARTIAL, PaymentStatus.PARTIALLY_REFUNDED),
            new PaymentTransition(PaymentStatus.PARTIALLY_REFUNDED, PaymentEvent.REFUND_ALL, PaymentStatus.REFUNDED),
            new PaymentTransition(PaymentStatus.PARTIALLY_REFUNDED, PaymentEvent.SUSPEND, PaymentStatus.SUSPENDED));

    private PaymentTransitionPolicy() {}

    public static List<PaymentTransition> transitions() {
        return TRANSITIONS;
    }

    public static Optional<PaymentStatus> nextStatus(PaymentStatus currentStatus, PaymentEvent event) {
        return TRANSITIONS.stream()
                .filter(transition -> transition.source() == currentStatus && transition.event() == event)
                .map(PaymentTransition::target)
                .findFirst();
    }
}
