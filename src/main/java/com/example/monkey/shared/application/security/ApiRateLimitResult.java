package com.example.monkey.shared.application.security;

public record ApiRateLimitResult(boolean allowed, long retryAfterSeconds) {}
