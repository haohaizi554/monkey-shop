package com.example.monkey.cart.domain;

import java.time.Duration;
import java.util.List;

public interface CartCleanupScheduler {

    void schedule(Long checkoutId, Long userId, List<CartItem> itemSnapshots, Duration cartTtl);
}
