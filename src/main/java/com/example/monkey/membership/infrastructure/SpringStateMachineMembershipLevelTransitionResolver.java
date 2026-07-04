package com.example.monkey.membership.infrastructure;

import com.example.monkey.membership.domain.MembershipLevel;
import com.example.monkey.membership.domain.MembershipLevelTransition;
import com.example.monkey.membership.domain.MembershipLevelTransitionPolicy;
import com.example.monkey.membership.domain.MembershipLevelTransitionResolver;
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
@ConditionalOnProperty(name = "app.membership.level-transition-resolver", havingValue = "spring", matchIfMissing = true)
public class SpringStateMachineMembershipLevelTransitionResolver implements MembershipLevelTransitionResolver {

    private final StateMachineFactory<MembershipLevel, MembershipLevelTransition> stateMachineFactory;

    public SpringStateMachineMembershipLevelTransitionResolver() {
        this.stateMachineFactory = buildFactory();
    }

    @Override
    public void assertAllowed(MembershipLevel currentLevel, MembershipLevel nextLevel) {
        MembershipLevelTransition transition = MembershipLevelTransitionPolicy.transition(currentLevel, nextLevel)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.CONFLICT, MembershipLevelTransitionPolicy.LEVEL_TRANSITION_NOT_ALLOWED));
        if (transition == MembershipLevelTransition.KEEP) {
            return;
        }
        StateMachine<MembershipLevel, MembershipLevelTransition> stateMachine =
                stateMachineFactory.getStateMachine(UUID.randomUUID());
        try {
            stateMachine
                    .getStateMachineAccessor()
                    .doWithAllRegions(access ->
                            access.resetStateMachine(new DefaultStateMachineContext<>(currentLevel, null, null, null)));
            stateMachine.start();
            boolean accepted = stateMachine.sendEvent(transition);
            if (!accepted || stateMachine.getState().getId() != nextLevel) {
                throw new BusinessException(
                        ErrorCode.CONFLICT, MembershipLevelTransitionPolicy.LEVEL_TRANSITION_NOT_ALLOWED);
            }
        } finally {
            stateMachine.stop();
        }
    }

    private static StateMachineFactory<MembershipLevel, MembershipLevelTransition> buildFactory() {
        try {
            StateMachineBuilder.Builder<MembershipLevel, MembershipLevelTransition> builder =
                    StateMachineBuilder.builder();
            builder.configureStates()
                    .withStates()
                    .initial(MembershipLevel.BASIC)
                    .states(EnumSet.allOf(MembershipLevel.class));
            var transitions = builder.configureTransitions();
            for (var transition : MembershipLevelTransitionPolicy.transitions()) {
                transitions = transitions
                        .withExternal()
                        .source(transition.source())
                        .target(transition.target())
                        .event(transition.transition())
                        .and();
            }
            return builder.createFactory();
        } catch (Exception e) {
            throw new IllegalStateException("Membership state machine could not be configured", e);
        }
    }
}
