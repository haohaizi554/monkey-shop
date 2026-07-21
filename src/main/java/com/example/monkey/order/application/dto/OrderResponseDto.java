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
                legacyLines(productId, shopId, productName, productImage, price, originalAmount, discountAmount));
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

    private static List<OrderLineResponseDto> legacyLines(
            Long productId,
            Long shopId,
            String productName,
            String productImage,
            BigDecimal price,
            BigDecimal originalAmount,
            BigDecimal discountAmount) {
        if (productId == null) {
            return List.of();
        }
        BigDecimal payable = price == null ? BigDecimal.ZERO : price;
        BigDecimal original = originalAmount == null ? payable : originalAmount;
        BigDecimal discount = discountAmount == null ? BigDecimal.ZERO : discountAmount;
        return List.of(new OrderLineResponseDto(
                null,
                productId,
                shopId,
                null,
                productName,
                productImage,
                1,
                payable,
                original,
                discount,
                payable,
                List.of()));
    }
}
