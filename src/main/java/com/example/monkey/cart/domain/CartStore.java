package com.example.monkey.cart.domain;

import java.time.Duration;
import java.util.List;

public interface CartStore {

    CartSnapshot findCart(Long userId);

    boolean putItemIfUnchanged(Long userId, CartItem expectedItem, CartItem item, Duration ttl);

    void putItem(Long userId, CartItem item, Duration ttl);

    void removeItem(Long userId, Long skuId, Duration ttl);

    void removeMatchingItems(Long userId, List<CartItem> expectedItems, Duration ttl);
}
