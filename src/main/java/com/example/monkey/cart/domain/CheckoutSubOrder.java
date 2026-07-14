package com.example.monkey.cart.domain;

import java.math.BigDecimal;
import java.util.List;

public record CheckoutSubOrder(
        Long id,
        Long shopId,
        String orderNo,
        BigDecimal originalAmount,
        BigDecimal storeDiscountAmount,
        BigDecimal platformDiscountAmount,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        Long formalOrderId,
        CartCheckoutStatus status,
        List<CheckoutLine> lines) {

    public CheckoutSubOrder(
            Long id,
            Long shopId,
            String orderNo,
            BigDecimal originalAmount,
            BigDecimal discountAmount,
            BigDecimal payableAmount,
            CartCheckoutStatus status,
            List<CheckoutLine> lines) {
        this(
                id,
                shopId,
                orderNo,
                originalAmount,
                discountAmount,
                BigDecimal.ZERO,
                discountAmount,
                payableAmount,
                null,
                status,
                lines);
    }

    public CheckoutSubOrder {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public CheckoutSubOrder withFormalOrderId(Long orderId) {
        return new CheckoutSubOrder(
                id,
                shopId,
                orderNo,
                originalAmount,
                storeDiscountAmount,
                platformDiscountAmount,
                discountAmount,
                payableAmount,
                orderId,
                status,
                lines);
    }
}
