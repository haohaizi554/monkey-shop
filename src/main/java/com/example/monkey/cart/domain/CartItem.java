package com.example.monkey.cart.domain;

import java.time.LocalDateTime;

public record CartItem(
        Long skuId, Long shopId, int quantity, boolean selected, LocalDateTime addedAt, LocalDateTime updatedAt) {

    public static final int MAX_QUANTITY = 999;

    public CartItem {
        if (skuId == null) {
            throw new IllegalArgumentException("SKU id is required");
        }
        if (shopId == null) {
            throw new IllegalArgumentException("shop id is required");
        }
        validateQuantity(quantity);
        LocalDateTime now = LocalDateTime.now();
        addedAt = addedAt == null ? now : addedAt;
        updatedAt = updatedAt == null ? addedAt : updatedAt;
    }

    public CartItem add(int delta, LocalDateTime now) {
        return withQuantity(quantity + delta, now);
    }

    public CartItem withQuantity(int nextQuantity, LocalDateTime now) {
        validateQuantity(nextQuantity);
        return new CartItem(skuId, shopId, nextQuantity, selected, addedAt, now);
    }

    public CartItem select(boolean nextSelected, LocalDateTime now) {
        return new CartItem(skuId, shopId, quantity, nextSelected, addedAt, now);
    }

    private static void validateQuantity(int quantity) {
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("quantity must be between 1 and " + MAX_QUANTITY);
        }
    }
}
