package com.example.monkey.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.monkey.order.domain.OrderEvent;
import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class SpringStateMachineOrderTransitionResolverTest {

    private final SpringStateMachineOrderTransitionResolver transitionResolver =
            new SpringStateMachineOrderTransitionResolver();

    @Test
    void configuredGraphAllowsBusinessOrderFlow() {
        assertThat(transitionResolver.nextStatus(OrderStatus.PAID, OrderEvent.SHIP))
                .isEqualTo(OrderStatus.SHIPPED);
        assertThat(transitionResolver.nextStatus(OrderStatus.SHIPPED, OrderEvent.RECEIVE))
                .isEqualTo(OrderStatus.COMPLETED);
        assertThat(transitionResolver.nextStatus(OrderStatus.COMPLETED, OrderEvent.REQUEST_RETURN))
                .isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(transitionResolver.nextStatus(OrderStatus.RETURN_REQUESTED, OrderEvent.APPROVE_RETURN))
                .isEqualTo(OrderStatus.WAITING_RETURN_SHIPMENT);
        assertThat(transitionResolver.nextStatus(OrderStatus.WAITING_RETURN_SHIPMENT, OrderEvent.SHIP_RETURN))
                .isEqualTo(OrderStatus.RETURN_SHIPPING);
        assertThat(transitionResolver.nextStatus(OrderStatus.RETURN_SHIPPING, OrderEvent.REFUND))
                .isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void configuredGraphRejectsIllegalTransition() {
        assertThatThrownBy(() -> transitionResolver.nextStatus(OrderStatus.PAID, OrderEvent.RECEIVE))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
}
