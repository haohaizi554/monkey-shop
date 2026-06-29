package com.example.monkey.domain.security;

public interface ApiRateLimiter {

    RateLimitDecision consume(RateLimitPolicy policy, String clientIp, String userKey);

    boolean isBlocked(String clientIp);

    void blockForHoneypot(String clientIp);

    record RateLimitDecision(boolean allowed, RateLimitPolicy policy, long retryAfterSeconds) {
        public static RateLimitDecision allowedDecision() {
            return new RateLimitDecision(true, null, 0);
        }

        public static RateLimitDecision rejected(RateLimitPolicy policy, long retryAfterSeconds) {
            return new RateLimitDecision(false, policy, Math.max(1L, retryAfterSeconds));
        }
    }
}
