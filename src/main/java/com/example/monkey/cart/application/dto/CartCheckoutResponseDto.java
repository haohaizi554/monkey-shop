package com.example.monkey.cart.application.dto;

import com.example.monkey.cart.domain.CartCheckoutStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record CartCheckoutResponseDto(
        Long id,
        String checkoutNo,
        Long userId,
        Long addressId,
        BigDecimal originalAmount,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        CartCheckoutStatus status,
        String province,
        LocalDateTime createdAt,
        List<CartSubOrderResponseDto> subOrders) {
    public CartCheckoutResponseDto {
        subOrders = subOrders == null ? List.of() : List.copyOf(subOrders);
    }
}
