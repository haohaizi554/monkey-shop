package com.example.monkey.marketing.domain;

import java.time.Duration;

public interface MarketingIdempotencyStore {

    boolean reserve(String scope, Long userId, String idempotencyKey, String requestHash, Duration ttl);
}
