package com.example.monkey.shared.domain.security;

import java.time.Duration;

public enum RateLimitPolicy {
    LOGIN("login", 5, Duration.ofMinutes(1)),
    REGISTER("register", 120, Duration.ofHours(1)),
    ORDER("order", 10, Duration.ofMinutes(1)),
    SECKILL("seckill", 60, Duration.ofMinutes(1)),
    CART("cart", 10, Duration.ofSeconds(1)),
    PAYMENT("payment", 5, Duration.ofSeconds(1)),
    LOGISTICS("logistics", 20, Duration.ofSeconds(1)),
    MEMBERSHIP("membership", 10, Duration.ofSeconds(1)),
    SEARCH("search", 30, Duration.ofMinutes(1)),
    RISK("risk", 20, Duration.ofSeconds(1)),
    TRACKING("tracking", 60, Duration.ofSeconds(1)),
    TENANT("tenant", 30, Duration.ofSeconds(1)),
    UPLOAD("upload", 10, Duration.ofMinutes(1)),
    DEFAULT("default", 120, Duration.ofMinutes(1));

    private final String key;
    private final long capacity;
    private final Duration window;

    RateLimitPolicy(String key, long capacity, Duration window) {
        this.key = key;
        this.capacity = capacity;
        this.window = window;
    }

    public String key() {
        return key;
    }

    public long capacity() {
        return capacity;
    }

    public Duration window() {
        return window;
    }
}
