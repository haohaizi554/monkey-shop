package com.example.monkey.cart.domain;

import java.math.BigDecimal;
import java.util.List;

public record CheckoutLine(
        Long id,
        Long skuId,
        Long shopId,
        Long categoryId,
        String productName,
        String productImage,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal originalAmount,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        List<String> couponCodes,
        String reservationKey,
        Long warehouseId) {
    public CheckoutLine {
        couponCodes = couponCodes == null ? List.of() : List.copyOf(couponCodes);
    }
}
