package com.example.monkey.user.domain;

public interface LoginAttemptPolicy {

    LoginAttemptState evaluate(String username, String clientIp);

    void recordFailure(String username, String clientIp);

    void recordSuccess(String username, String clientIp);
}
