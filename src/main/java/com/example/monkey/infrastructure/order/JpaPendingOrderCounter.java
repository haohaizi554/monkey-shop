package com.example.monkey.infrastructure.order;

import com.example.monkey.domain.order.OrderStatus;
import com.example.monkey.domain.order.PendingOrderCounter;
import com.example.monkey.repository.OrderRepository;
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
