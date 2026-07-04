package com.example.monkey.logistics.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.monkey.logistics.domain.TrackingEvent;
import com.example.monkey.logistics.domain.TrackingStatus;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class SpringStateMachineLogisticsTransitionResolverTest {

    private final SpringStateMachineLogisticsTransitionResolver transitionResolver =
            new SpringStateMachineLogisticsTransitionResolver();

    @Test
    void configuredGraphAllowsOrderedToSignedFlow() {
        assertThat(transitionResolver.nextStatus(TrackingStatus.ORDERED, TrackingEvent.PICKUP))
                .isEqualTo(TrackingStatus.PICKED_UP);
        assertThat(transitionResolver.nextStatus(TrackingStatus.PICKED_UP, TrackingEvent.TRANSIT))
                .isEqualTo(TrackingStatus.IN_TRANSIT);
        assertThat(transitionResolver.nextStatus(TrackingStatus.IN_TRANSIT, TrackingEvent.DISPATCH))
                .isEqualTo(TrackingStatus.OUT_FOR_DELIVERY);
        assertThat(transitionResolver.nextStatus(TrackingStatus.OUT_FOR_DELIVERY, TrackingEvent.SIGN))
                .isEqualTo(TrackingStatus.SIGNED);
    }

    @Test
    void configuredGraphRejectsIllegalTransition() {
        assertThatThrownBy(() -> transitionResolver.nextStatus(TrackingStatus.ORDERED, TrackingEvent.SIGN))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
}
