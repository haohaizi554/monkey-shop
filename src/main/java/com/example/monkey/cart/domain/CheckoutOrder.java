package com.example.monkey.cart.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record CheckoutOrder(
        Long id,
        String checkoutNo,
        Long userId,
        Long addressId,
        String idempotencyKey,
        String requestFingerprint,
        BigDecimal originalAmount,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        CartCheckoutStatus status,
        String province,
        LocalDateTime createdAt,
        List<CheckoutSubOrder> subOrders) {

    public static final String LEGACY_V51_REQUEST_FINGERPRINT =
            "LEGACY_V51_CHECKOUT_REPLAY_SENTINEL_____________________________";

    public CheckoutOrder {
        subOrders = subOrders == null ? List.of() : List.copyOf(subOrders);
    }

    public List<Long> orderIds() {
        return subOrders.stream()
                .map(CheckoutSubOrder::formalOrderId)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public CheckoutOrder confirmed(List<Long> orderIds) {
        if (orderIds == null || orderIds.size() != subOrders.size()) {
            throw new IllegalArgumentException("Each checkout suborder must reference one formal order");
        }
        List<CheckoutSubOrder> confirmedSubOrders = java.util.stream.IntStream.range(0, subOrders.size())
                .mapToObj(index -> subOrders.get(index).withFormalOrderId(orderIds.get(index)))
                .toList();
        return new CheckoutOrder(
                id,
                checkoutNo,
                userId,
                addressId,
                idempotencyKey,
                requestFingerprint,
                originalAmount,
                discountAmount,
                payableAmount,
                status,
                province,
                createdAt,
                confirmedSubOrders);
    }
}
