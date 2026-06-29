package com.example.monkey.domain.order;

@FunctionalInterface
public interface PendingOrderCounter {
    long countPendingOrders();
}
