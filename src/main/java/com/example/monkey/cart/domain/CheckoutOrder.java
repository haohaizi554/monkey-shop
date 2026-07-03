package com.example.monkey.cart.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record CheckoutOrder(
        Long id,
        String checkoutNo,
        Long userId,
        Long addressId,
        String idempotencyKey,
        BigDecimal originalAmount,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        CartCheckoutStatus status,
        String province,
        LocalDateTime createdAt,
        List<CheckoutSubOrder> subOrders) {
    public CheckoutOrder {
        subOrders = subOrders == null ? List.of() : List.copyOf(subOrders);
    }
}
