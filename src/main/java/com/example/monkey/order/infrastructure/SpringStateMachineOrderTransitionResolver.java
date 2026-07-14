package com.example.monkey.order.infrastructure;

import com.example.monkey.order.domain.OrderEvent;
import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.order.domain.OrderTransitionPolicy;
import com.example.monkey.order.domain.OrderTransitionResolver;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.util.EnumSet;
import java.util.UUID;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.StateMachineEventResult;
import org.springframework.statemachine.config.StateMachineBuilder;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class SpringStateMachineOrderTransitionResolver implements OrderTransitionResolver {

    private final StateMachineFactory<OrderStatus, OrderEvent> stateMachineFactory;

    public SpringStateMachineOrderTransitionResolver() {
        this.stateMachineFactory = buildFactory();
    }

    @Override
    public OrderStatus nextStatus(OrderStatus currentStatus, OrderEvent event) {
        StateMachine<OrderStatus, OrderEvent> stateMachine = stateMachineFactory.getStateMachine(UUID.randomUUID());
        OrderStatus expectedNextStatus = OrderTransitionPolicy.nextStatus(currentStatus, event)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.CONFLICT, OrderTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED));
        try {
            stateMachine
                    .getStateMachineAccessor()
                    .doWithAllRegions(access -> access.resetStateMachineReactively(
                                    new DefaultStateMachineContext<>(currentStatus, null, null, null))
                            .block());
            stateMachine.startReactively().block();
            boolean accepted = Boolean.TRUE.equals(stateMachine
                    .sendEvent(Mono.just(MessageBuilder.withPayload(event).build()))
                    .any(result -> result.getResultType() == StateMachineEventResult.ResultType.ACCEPTED)
                    .block());
            OrderStatus nextStatus = stateMachine.getState().getId();
            if (!accepted || nextStatus != expectedNextStatus) {
                throw new BusinessException(ErrorCode.CONFLICT, OrderTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED);
            }
            return nextStatus;
        } finally {
            stateMachine.stopReactively().block();
        }
    }

    private static StateMachineFactory<OrderStatus, OrderEvent> buildFactory() {
        try {
            StateMachineBuilder.Builder<OrderStatus, OrderEvent> builder = StateMachineBuilder.builder();
            builder.configureStates()
                    .withStates()
                    .initial(OrderStatus.PENDING_PAYMENT)
                    .states(EnumSet.allOf(OrderStatus.class));
            var transitions = builder.configureTransitions();
            for (var transition : OrderTransitionPolicy.transitions()) {
                transitions = transitions
                        .withExternal()
                        .source(transition.source())
                        .target(transition.target())
                        .event(transition.event())
                        .and();
            }
            return builder.createFactory();
        } catch (Exception e) {
            throw new IllegalStateException("Order state machine could not be configured", e);
        }
    }
}
