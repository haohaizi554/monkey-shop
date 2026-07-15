package com.example.monkey.cart.domain;

import java.time.Duration;
import java.util.List;

public interface CartCleanupScheduler {

    void schedule(Long checkoutId, Long userId, List<Long> skuIds, Duration cartTtl);
}
