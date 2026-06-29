package com.example.monkey.domain.order;

public interface OrderTransitionResolver {
    OrderStatus nextStatus(OrderStatus currentStatus, OrderEvent event);
}
