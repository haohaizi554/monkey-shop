package com.example.monkey.domain.order;

import java.util.List;
import java.util.Optional;

public final class OrderTransitionPolicy {

    public static final String STATUS_TRANSITION_NOT_ALLOWED = "Order status does not allow this operation";

    private static final List<OrderTransition> TRANSITIONS = List.of(
            new OrderTransition(OrderStatus.PAID, OrderEvent.SHIP, OrderStatus.SHIPPED),
            new OrderTransition(OrderStatus.SHIPPED, OrderEvent.RECEIVE, OrderStatus.COMPLETED),
            new OrderTransition(OrderStatus.COMPLETED, OrderEvent.REQUEST_RETURN, OrderStatus.RETURN_REQUESTED),
            new OrderTransition(
                    OrderStatus.RETURN_REQUESTED, OrderEvent.APPROVE_RETURN, OrderStatus.WAITING_RETURN_SHIPMENT),
            new OrderTransition(
                    OrderStatus.WAITING_RETURN_SHIPMENT, OrderEvent.SHIP_RETURN, OrderStatus.RETURN_SHIPPING),
            new OrderTransition(OrderStatus.RETURN_SHIPPING, OrderEvent.REFUND, OrderStatus.REFUNDED));

    private OrderTransitionPolicy() {}

    public static List<OrderTransition> transitions() {
        return TRANSITIONS;
    }

    public static Optional<OrderStatus> nextStatus(OrderStatus currentStatus, OrderEvent event) {
        return TRANSITIONS.stream()
                .filter(transition -> transition.source() == currentStatus && transition.event() == event)
                .map(OrderTransition::target)
                .findFirst();
    }
}
