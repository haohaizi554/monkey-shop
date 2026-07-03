package com.example.monkey.cart.application.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CartItemResponseDto(
        Long skuId,
        Long shopId,
        String productName,
        String productImage,
        BigDecimal unitPrice,
        int quantity,
        boolean selected,
        BigDecimal lineAmount,
        LocalDateTime updatedAt) {}
