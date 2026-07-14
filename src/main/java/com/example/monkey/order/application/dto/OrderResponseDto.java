package com.example.monkey.order.application.dto;

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
        LocalDateTime createTime,
        Long checkoutId,
        Long checkoutSubOrderId,
        Long shopId,
        BigDecimal originalAmount,
        BigDecimal discountAmount,
        String checkoutIdempotencyKey) {

    public OrderResponseDto(
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
            LocalDateTime createTime) {
        this(
                id,
                orderNo,
                userId,
                buyerName,
                buyerAvatar,
                productId,
                productName,
                productImage,
                price,
                description,
                receiverName,
                receiverPhone,
                addressSnapshot,
                shippingTime,
                status,
                createTime,
                null,
                null,
                null,
                price,
                BigDecimal.ZERO,
                null);
    }
}
