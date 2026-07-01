package com.example.monkey.order.infrastructure;

import com.example.monkey.order.domain.OrderOwnershipChecker;
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
