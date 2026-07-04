package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.domain.PaymentEvent;
import com.example.monkey.payment.domain.PaymentStatus;
import com.example.monkey.payment.domain.PaymentTransitionPolicy;
import com.example.monkey.payment.domain.PaymentTransitionResolver;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.util.EnumSet;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineBuilder;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.payment.transition-resolver", havingValue = "spring", matchIfMissing = true)
public class SpringStateMachinePaymentTransitionResolver implements PaymentTransitionResolver {

    private final StateMachineFactory<PaymentStatus, PaymentEvent> stateMachineFactory;

    public SpringStateMachinePaymentTransitionResolver() {
        this.stateMachineFactory = buildFactory();
    }

    @Override
    public PaymentStatus nextStatus(PaymentStatus currentStatus, PaymentEvent event) {
        StateMachine<PaymentStatus, PaymentEvent> stateMachine = stateMachineFactory.getStateMachine(UUID.randomUUID());
        PaymentStatus expectedNextStatus = PaymentTransitionPolicy.nextStatus(currentStatus, event)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.CONFLICT, PaymentTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED));
        try {
            stateMachine
                    .getStateMachineAccessor()
                    .doWithAllRegions(access -> access.resetStateMachine(
                            new DefaultStateMachineContext<>(currentStatus, null, null, null)));
            stateMachine.start();
            boolean accepted = stateMachine.sendEvent(event);
            PaymentStatus nextStatus = stateMachine.getState().getId();
            if (!accepted || nextStatus != expectedNextStatus) {
                throw new BusinessException(ErrorCode.CONFLICT, PaymentTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED);
            }
            return nextStatus;
        } finally {
            stateMachine.stop();
        }
    }

    private static StateMachineFactory<PaymentStatus, PaymentEvent> buildFactory() {
        try {
            StateMachineBuilder.Builder<PaymentStatus, PaymentEvent> builder = StateMachineBuilder.builder();
            builder.configureStates()
                    .withStates()
                    .initial(PaymentStatus.PENDING)
                    .states(EnumSet.allOf(PaymentStatus.class));
            var transitions = builder.configureTransitions();
            for (var transition : PaymentTransitionPolicy.transitions()) {
                transitions = transitions
                        .withExternal()
                        .source(transition.source())
                        .target(transition.target())
                        .event(transition.event())
                        .and();
            }
            return builder.createFactory();
        } catch (Exception e) {
            throw new IllegalStateException("Payment state machine could not be configured", e);
        }
    }
}
