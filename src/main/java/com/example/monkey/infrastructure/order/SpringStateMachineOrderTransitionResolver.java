package com.example.monkey.infrastructure.order;

import com.example.monkey.domain.order.OrderEvent;
import com.example.monkey.domain.order.OrderStatus;
import com.example.monkey.domain.order.OrderTransitionPolicy;
import com.example.monkey.domain.order.OrderTransitionResolver;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.exception.BusinessException;
import java.util.EnumSet;
import java.util.UUID;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineBuilder;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Component;

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
                    .doWithAllRegions(access -> access.resetStateMachine(
                            new DefaultStateMachineContext<>(currentStatus, null, null, null)));
            stateMachine.start();
            boolean accepted = stateMachine.sendEvent(event);
            OrderStatus nextStatus = stateMachine.getState().getId();
            if (!accepted || nextStatus != expectedNextStatus) {
                throw new BusinessException(ErrorCode.CONFLICT, OrderTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED);
            }
            return nextStatus;
        } finally {
            stateMachine.stop();
        }
    }

    private static StateMachineFactory<OrderStatus, OrderEvent> buildFactory() {
        try {
            StateMachineBuilder.Builder<OrderStatus, OrderEvent> builder = StateMachineBuilder.builder();
            builder.configureStates().withStates().initial(OrderStatus.PAID).states(EnumSet.allOf(OrderStatus.class));
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
