package com.example.monkey.shared.domain.security;

public interface ApiRateLimiter {

    RateLimitDecision consume(RateLimitPolicy policy, String clientIp, String userKey);

    RateLimitDecision consumeRegistrationIdentity(RegistrationIdentity identity, String rawIdentity);

    boolean isBlocked(String clientIp);

    void blockForHoneypot(String clientIp);

    enum RegistrationIdentity {
        USERNAME("username"),
        PHONE("phone");

        private final String key;

        RegistrationIdentity(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    record RateLimitDecision(boolean allowed, RateLimitPolicy policy, long retryAfterSeconds) {
        public static RateLimitDecision allowedDecision() {
            return new RateLimitDecision(true, null, 0);
        }

        public static RateLimitDecision rejected(RateLimitPolicy policy, long retryAfterSeconds) {
            return new RateLimitDecision(false, policy, Math.max(1L, retryAfterSeconds));
        }
    }
}
