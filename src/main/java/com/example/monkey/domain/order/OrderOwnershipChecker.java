package com.example.monkey.domain.order;

public interface OrderOwnershipChecker {

    boolean isVisibleOwner(Long orderId, Long userId);
}
