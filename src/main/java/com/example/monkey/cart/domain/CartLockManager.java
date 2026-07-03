package com.example.monkey.cart.domain;

import java.util.function.Supplier;

public interface CartLockManager {

    <T> T withCheckoutLock(Long userId, String idempotencyKey, Supplier<T> action);
}
