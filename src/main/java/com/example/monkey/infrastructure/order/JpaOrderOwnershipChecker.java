package com.example.monkey.infrastructure.order;

import com.example.monkey.domain.order.OrderOwnershipChecker;
import com.example.monkey.repository.OrderRepository;
import org.springframework.stereotype.Component;

@Component
public class JpaOrderOwnershipChecker implements OrderOwnershipChecker {

    private final OrderRepository orderRepository;

    public JpaOrderOwnershipChecker(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public boolean isVisibleOwner(Long orderId, Long userId) {
        return orderRepository.existsByIdAndUserIdAndUserHiddenFalse(orderId, userId);
    }
}
