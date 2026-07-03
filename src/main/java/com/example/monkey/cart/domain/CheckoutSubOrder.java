package com.example.monkey.cart.domain;

import java.math.BigDecimal;
import java.util.List;

public record CheckoutSubOrder(
        Long id,
        Long shopId,
        String orderNo,
        BigDecimal originalAmount,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        CartCheckoutStatus status,
        List<CheckoutLine> lines) {
    public CheckoutSubOrder {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
