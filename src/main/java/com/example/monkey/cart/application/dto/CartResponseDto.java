package com.example.monkey.cart.application.dto;

import java.math.BigDecimal;
import java.util.List;

public record CartResponseDto(
        Long userId, List<CartItemResponseDto> items, int selectedQuantity, BigDecimal selectedAmount) {
    public CartResponseDto {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
