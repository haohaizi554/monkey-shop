package com.example.monkey.security;

import com.example.monkey.domain.order.OrderOwnershipChecker;
import com.example.monkey.domain.user.SessionUser;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("orderOwnership")
public class OrderOwnership {

    private final OrderOwnershipChecker orderOwnershipChecker;

    public OrderOwnership(OrderOwnershipChecker orderOwnershipChecker) {
        this.orderOwnershipChecker = orderOwnershipChecker;
    }

    public boolean isOwner(Long orderId, Authentication authentication) {
        if (orderId == null || authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof SessionUser sessionUser)) {
            return false;
        }
        return orderOwnershipChecker.isVisibleOwner(orderId, sessionUser.id());
    }
}
