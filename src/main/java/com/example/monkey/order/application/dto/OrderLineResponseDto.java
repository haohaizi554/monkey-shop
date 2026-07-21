package com.example.monkey.order.application.dto;

import java.math.BigDecimal;
import java.util.List;

public record OrderLineResponseDto(
        Long checkoutLineId,
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
        List<String> couponCodes) {

    public OrderLineResponseDto {
        couponCodes = couponCodes == null ? List.of() : List.copyOf(couponCodes);
    }
}
