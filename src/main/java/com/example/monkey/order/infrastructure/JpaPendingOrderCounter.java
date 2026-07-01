package com.example.monkey.order.infrastructure;

import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.order.domain.PendingOrderCounter;
import org.springframework.stereotype.Component;

@Component
public final class JpaPendingOrderCounter implements PendingOrderCounter {

    private final OrderRepository orderRepository;

    public JpaPendingOrderCounter(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public long countPendingOrders() {
        return orderRepository.countByStatus(OrderStatus.PAID.label());
    }
}
