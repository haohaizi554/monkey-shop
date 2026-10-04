package com.example.monkey.marketing.domain;

import java.time.Duration;
import java.util.Optional;

public interface MarketingIdempotencyStore {

    boolean reserve(String scope, Long userId, String idempotencyKey, String requestHash, Duration ttl);

    default Optional<String> find(String scope, Long userId, String idempotencyKey) {
        return Optional.empty();
    }
}
