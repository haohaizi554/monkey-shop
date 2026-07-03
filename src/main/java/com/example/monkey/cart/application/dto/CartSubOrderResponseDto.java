package com.example.monkey.cart.application.dto;

import com.example.monkey.cart.domain.CartCheckoutStatus;
import java.math.BigDecimal;
import java.util.List;

public record CartSubOrderResponseDto(
        Long id,
        Long shopId,
        String orderNo,
        BigDecimal originalAmount,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        CartCheckoutStatus status,
        List<CartCheckoutLineResponseDto> lines) {
    public CartSubOrderResponseDto {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
