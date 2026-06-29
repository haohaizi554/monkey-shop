package com.example.monkey.domain.order;

import java.time.Duration;

public interface OrderIdempotencyKeyStore {

    void reserve(Long userId, String idempotencyKey, String requestHash, Duration ttl);
}
