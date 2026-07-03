package com.example.monkey.shared.application.security;

import com.example.monkey.shared.domain.security.ApiRateLimiter;
import com.example.monkey.shared.domain.security.RateLimitPolicy;
import org.springframework.stereotype.Service;

@Service
public class ApiRateLimitApplicationService {

    private final ApiRateLimiter rateLimiter;

    public ApiRateLimitApplicationService(ApiRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    public ApiRateLimitResult consume(ApiRateLimitOperation operation, String clientIp, String userKey) {
        return consumePolicy(toPolicy(operation), clientIp, userKey);
    }

    public ApiRateLimitResult consumePolicy(RateLimitPolicy policy, String clientIp, String userKey) {
        ApiRateLimiter.RateLimitDecision decision =
                rateLimiter.consume(policy == null ? RateLimitPolicy.DEFAULT : policy, clientIp, userKey);
        return new ApiRateLimitResult(decision.allowed(), decision.retryAfterSeconds());
    }

    public boolean isBlocked(String clientIp) {
        return rateLimiter.isBlocked(clientIp);
    }

    public void blockForHoneypot(String clientIp) {
        rateLimiter.blockForHoneypot(clientIp);
    }

    private static RateLimitPolicy toPolicy(ApiRateLimitOperation operation) {
        return switch (operation == null ? ApiRateLimitOperation.DEFAULT : operation) {
            case LOGIN -> RateLimitPolicy.LOGIN;
            case REGISTER -> RateLimitPolicy.REGISTER;
            case ORDER -> RateLimitPolicy.ORDER;
            case SECKILL -> RateLimitPolicy.SECKILL;
            case SEARCH -> RateLimitPolicy.SEARCH;
            case UPLOAD -> RateLimitPolicy.UPLOAD;
            case DEFAULT -> RateLimitPolicy.DEFAULT;
        };
    }
}
