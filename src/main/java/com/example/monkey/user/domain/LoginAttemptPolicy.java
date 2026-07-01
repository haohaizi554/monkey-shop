package com.example.monkey.user.domain;

public interface LoginAttemptPolicy {

    void enforceAllowed(String username, String clientIp);

    boolean requiresCaptcha(String username, String clientIp);

    void recordFailure(String username, String clientIp);

    void recordSuccess(String username, String clientIp);
}
