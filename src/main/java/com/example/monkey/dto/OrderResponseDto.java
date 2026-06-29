package com.example.monkey.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderResponseDto(
        Long id,
        String orderNo,
        Long userId,
        String buyerName,
        String buyerAvatar,
        Long productId,
        String productName,
        String productImage,
        BigDecimal price,
        String description,
        String receiverName,
        String receiverPhone,
        String addressSnapshot,
        LocalDateTime shippingTime,
        String status,
        LocalDateTime createTime) {}
