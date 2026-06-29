package com.example.monkey.domain.order;

public record OrderTransition(OrderStatus source, OrderEvent event, OrderStatus target) {}
