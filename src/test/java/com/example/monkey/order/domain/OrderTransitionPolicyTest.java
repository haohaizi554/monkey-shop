package com.example.monkey.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class OrderTransitionPolicyTest {

    @Test
    void exposesCanonicalBusinessTransitionGraph() {
        assertThat(OrderTransitionPolicy.transitions())
                .contains(
                        new OrderTransition(OrderStatus.PAID, OrderEvent.SHIP_PARTIAL, OrderStatus.PARTIALLY_SHIPPED),
                        new OrderTransition(OrderStatus.PAID, OrderEvent.SHIP, OrderStatus.SHIPPED),
                        new OrderTransition(
                                OrderStatus.PARTIALLY_SHIPPED, OrderEvent.SHIP_PARTIAL, OrderStatus.PARTIALLY_SHIPPED),
                        new OrderTransition(OrderStatus.PARTIALLY_SHIPPED, OrderEvent.SHIP, OrderStatus.SHIPPED),
                        new OrderTransition(
                                OrderStatus.SHIPPED, OrderEvent.RECEIVE_PARTIAL, OrderStatus.PARTIALLY_RECEIVED),
                        new OrderTransition(
                                OrderStatus.PARTIALLY_RECEIVED,
                                OrderEvent.RECEIVE_PARTIAL,
                                OrderStatus.PARTIALLY_RECEIVED),
                        new OrderTransition(OrderStatus.PARTIALLY_RECEIVED, OrderEvent.RECEIVE, OrderStatus.COMPLETED),
                        new OrderTransition(OrderStatus.SHIPPED, OrderEvent.RECEIVE, OrderStatus.COMPLETED),
                        new OrderTransition(
                                OrderStatus.COMPLETED, OrderEvent.REQUEST_RETURN, OrderStatus.RETURN_REQUESTED),
                        new OrderTransition(
                                OrderStatus.RETURN_REQUESTED,
                                OrderEvent.APPROVE_RETURN,
                                OrderStatus.WAITING_RETURN_SHIPMENT),
                        new OrderTransition(
                                OrderStatus.WAITING_RETURN_SHIPMENT,
                                OrderEvent.SHIP_RETURN,
                                OrderStatus.RETURN_SHIPPING),
                        new OrderTransition(OrderStatus.RETURN_SHIPPING, OrderEvent.REFUND, OrderStatus.REFUNDED));
    }

    @Test
    void resolvesAllowedTransitionsOnly() {
        assertThat(OrderTransitionPolicy.nextStatus(OrderStatus.PAID, OrderEvent.SHIP))
                .contains(OrderStatus.SHIPPED);
        assertThat(OrderTransitionPolicy.nextStatus(OrderStatus.PAID, OrderEvent.SHIP_PARTIAL))
                .contains(OrderStatus.PARTIALLY_SHIPPED);
        assertThat(OrderTransitionPolicy.nextStatus(OrderStatus.PAID, OrderEvent.RECEIVE))
                .isEmpty();
    }

    @Test
    void transitionGraphHasOneTargetPerSourceAndEvent() {
        List<String> keys = OrderTransitionPolicy.transitions().stream()
                .map(transition -> transition.source() + ":" + transition.event())
                .toList();

        assertThat(keys).doesNotHaveDuplicates();
    }
}
