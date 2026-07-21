package com.example.monkey.order.application.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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
        String checkoutIdempotencyKey,
        List<OrderLineResponseDto> lines) {

    public OrderResponseDto {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

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
            LocalDateTime createTime,
            Long checkoutId,
            Long checkoutSubOrderId,
            Long shopId,
            BigDecimal originalAmount,
            BigDecimal discountAmount,
            String checkoutIdempotencyKey) {
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
                checkoutId,
                checkoutSubOrderId,
                shopId,
                originalAmount,
                discountAmount,
                checkoutIdempotencyKey,
                List.of());
    }

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
