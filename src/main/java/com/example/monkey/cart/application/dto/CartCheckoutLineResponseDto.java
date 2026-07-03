package com.example.monkey.cart.application.dto;

import java.math.BigDecimal;
import java.util.List;

public record CartCheckoutLineResponseDto(
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
    public CartCheckoutLineResponseDto {
        couponCodes = couponCodes == null ? List.of() : List.copyOf(couponCodes);
    }
}
