package com.example.monkey.order.domain;

import java.time.Duration;

public interface OrderIdempotencyKeyStore {

    void reserve(Long userId, String idempotencyKey, String requestHash, Duration ttl);
}
