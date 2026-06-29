package com.example.monkey.order.interfaces;

import com.example.monkey.order.application.OrderOwnershipService;
import com.example.monkey.shared.application.security.SessionUser;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("orderOwnership")
public class OrderOwnership {

    private final OrderOwnershipService orderOwnershipService;

    public OrderOwnership(OrderOwnershipService orderOwnershipService) {
        this.orderOwnershipService = orderOwnershipService;
    }

    public boolean isOwner(Long orderId, Authentication authentication) {
        if (orderId == null || authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof SessionUser sessionUser)) {
            return false;
        }
        return orderOwnershipService.isVisibleOwner(orderId, sessionUser.id());
    }
}
