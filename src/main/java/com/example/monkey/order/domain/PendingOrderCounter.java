package com.example.monkey.order.domain;

@FunctionalInterface
public interface PendingOrderCounter {
    long countPendingOrders();
}
