package com.example.monkey.domain.security;

import java.time.Duration;

public enum RateLimitPolicy {
    LOGIN("login", 5, Duration.ofMinutes(1)),
    REGISTER("register", 3, Duration.ofHours(1)),
    ORDER("order", 10, Duration.ofMinutes(1)),
    SEARCH("search", 30, Duration.ofMinutes(1)),
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
