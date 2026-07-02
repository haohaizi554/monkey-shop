package com.example.monkey.order.application;

import com.example.monkey.order.domain.OrderOwnershipChecker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderOwnershipService {

    private final OrderOwnershipChecker orderOwnershipChecker;

    public OrderOwnershipService(OrderOwnershipChecker orderOwnershipChecker) {
        this.orderOwnershipChecker = orderOwnershipChecker;
    }

    @Transactional(readOnly = true)
    public boolean isVisibleOwner(Long orderId, Long userId) {
        if (orderId == null || userId == null) {
            return false;
        }
        return orderOwnershipChecker.isVisibleOwner(orderId, userId);
    }
}
