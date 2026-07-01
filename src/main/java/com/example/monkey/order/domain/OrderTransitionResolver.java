package com.example.monkey.order.domain;

public interface OrderTransitionResolver {
    OrderStatus nextStatus(OrderStatus currentStatus, OrderEvent event);
}
