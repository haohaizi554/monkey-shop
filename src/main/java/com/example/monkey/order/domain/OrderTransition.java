package com.example.monkey.order.domain;

public record OrderTransition(OrderStatus source, OrderEvent event, OrderStatus target) {}
