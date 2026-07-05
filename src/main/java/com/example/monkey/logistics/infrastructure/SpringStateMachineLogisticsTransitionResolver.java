package com.example.monkey.logistics.infrastructure;

import com.example.monkey.logistics.domain.LogisticsTransitionPolicy;
import com.example.monkey.logistics.domain.LogisticsTransitionResolver;
import com.example.monkey.logistics.domain.TrackingEvent;
import com.example.monkey.logistics.domain.TrackingStatus;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.util.EnumSet;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.StateMachineEventResult;
import org.springframework.statemachine.config.StateMachineBuilder;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "app.logistics.transition-resolver", havingValue = "spring", matchIfMissing = true)
public class SpringStateMachineLogisticsTransitionResolver implements LogisticsTransitionResolver {

    private final StateMachineFactory<TrackingStatus, TrackingEvent> stateMachineFactory;

    public SpringStateMachineLogisticsTransitionResolver() {
        this.stateMachineFactory = buildFactory();
    }

    @Override
    public TrackingStatus nextStatus(TrackingStatus currentStatus, TrackingEvent event) {
        StateMachine<TrackingStatus, TrackingEvent> stateMachine =
                stateMachineFactory.getStateMachine(UUID.randomUUID());
        TrackingStatus expectedNextStatus = LogisticsTransitionPolicy.nextStatus(currentStatus, event)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.CONFLICT, LogisticsTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED));
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
            TrackingStatus nextStatus = stateMachine.getState().getId();
            if (!accepted || nextStatus != expectedNextStatus) {
                throw new BusinessException(
                        ErrorCode.CONFLICT, LogisticsTransitionPolicy.STATUS_TRANSITION_NOT_ALLOWED);
            }
            return nextStatus;
        } finally {
            stateMachine.stopReactively().block();
        }
    }

    private static StateMachineFactory<TrackingStatus, TrackingEvent> buildFactory() {
        try {
            StateMachineBuilder.Builder<TrackingStatus, TrackingEvent> builder = StateMachineBuilder.builder();
            builder.configureStates()
                    .withStates()
                    .initial(TrackingStatus.ORDERED)
                    .states(EnumSet.allOf(TrackingStatus.class));
            var transitions = builder.configureTransitions();
            for (var transition : LogisticsTransitionPolicy.transitions()) {
                transitions = transitions
                        .withExternal()
                        .source(transition.source())
                        .target(transition.target())
                        .event(transition.event())
                        .and();
            }
            return builder.createFactory();
        } catch (Exception e) {
            throw new IllegalStateException("Logistics state machine could not be configured", e);
        }
    }
}
