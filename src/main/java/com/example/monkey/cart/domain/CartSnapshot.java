package com.example.monkey.cart.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record CartSnapshot(Long userId, List<CartItem> items) {

    public CartSnapshot {
        if (userId == null) {
            throw new IllegalArgumentException("user id is required");
        }
        items = items == null
                ? List.of()
                : items.stream()
                        .sorted(Comparator.comparing(CartItem::updatedAt).reversed())
                        .toList();
    }

    public CartSnapshot upsert(Long skuId, Long shopId, int quantity, boolean selected, LocalDateTime now) {
        List<CartItem> nextItems = new ArrayList<>();
        boolean found = false;
        for (CartItem item : items) {
            if (item.skuId().equals(skuId)) {
                nextItems.add(item.add(quantity, now).select(selected, now));
                found = true;
            } else {
                nextItems.add(item);
            }
        }
        if (!found) {
            nextItems.add(new CartItem(skuId, shopId, quantity, selected, now, now));
        }
        return new CartSnapshot(userId, nextItems);
    }

    public CartSnapshot changeQuantity(Long skuId, int quantity, LocalDateTime now) {
        return mapItem(skuId, item -> item.withQuantity(quantity, now));
    }

    public CartSnapshot select(Long skuId, boolean selected, LocalDateTime now) {
        return mapItem(skuId, item -> item.select(selected, now));
    }

    public CartSnapshot remove(Long skuId) {
        return new CartSnapshot(
                userId,
                items.stream().filter(item -> !item.skuId().equals(skuId)).toList());
    }

    public List<CartItem> selectedItems() {
        return items.stream().filter(CartItem::selected).toList();
    }

    private CartSnapshot mapItem(Long skuId, CartItemMapper mapper) {
        return new CartSnapshot(
                userId,
                items.stream()
                        .map(item -> item.skuId().equals(skuId) ? mapper.apply(item) : item)
                        .toList());
    }

    private interface CartItemMapper {
        CartItem apply(CartItem item);
    }
}
