package com.example.monkey.order.domain;

public interface OrderOwnershipChecker {

    boolean isVisibleOwner(Long orderId, Long userId);
}
