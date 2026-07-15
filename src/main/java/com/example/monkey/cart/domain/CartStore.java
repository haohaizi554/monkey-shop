package com.example.monkey.cart.domain;

import java.time.Duration;
import java.util.List;

public interface CartStore {

    CartSnapshot findCart(Long userId);

    CartSnapshot save(CartSnapshot cart, Duration ttl);

    void removeMatchingItems(Long userId, List<CartItem> expectedItems, Duration ttl);
}
