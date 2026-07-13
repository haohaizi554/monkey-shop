package com.example.monkey.user.application;

import com.example.monkey.user.domain.LoginAttemptPolicy;
import com.example.monkey.user.domain.LoginAttemptState;
import org.springframework.stereotype.Service;

@Service
public class LoginAttemptApplicationService {

    private final LoginAttemptPolicy loginAttemptPolicy;

    public LoginAttemptApplicationService(LoginAttemptPolicy loginAttemptPolicy) {
        this.loginAttemptPolicy = loginAttemptPolicy;
    }

    public LoginAttemptState evaluate(String username, String clientIp) {
        return loginAttemptPolicy.evaluate(username, clientIp);
    }

    public void recordFailure(String username, String clientIp) {
        loginAttemptPolicy.recordFailure(username, clientIp);
    }

    public void recordSuccess(String username, String clientIp) {
        loginAttemptPolicy.recordSuccess(username, clientIp);
    }
}
