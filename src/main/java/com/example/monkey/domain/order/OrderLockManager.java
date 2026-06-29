package com.example.monkey.domain.order;

import java.util.function.Supplier;

public interface OrderLockManager {

    <T> T withCreateOrderLock(Long userId, Long productId, Supplier<T> operation);
}
