package com.example.monkey.user.application;

import com.example.monkey.user.domain.LoginAttemptPolicy;
import org.springframework.stereotype.Service;

@Service
public class LoginAttemptApplicationService {

    private final LoginAttemptPolicy loginAttemptPolicy;

    public LoginAttemptApplicationService(LoginAttemptPolicy loginAttemptPolicy) {
        this.loginAttemptPolicy = loginAttemptPolicy;
    }

    public void enforceAllowed(String username, String clientIp) {
        loginAttemptPolicy.enforceAllowed(username, clientIp);
    }

    public boolean requiresCaptcha(String username, String clientIp) {
        return loginAttemptPolicy.requiresCaptcha(username, clientIp);
    }

    public void recordFailure(String username, String clientIp) {
        loginAttemptPolicy.recordFailure(username, clientIp);
    }

    public void recordSuccess(String username, String clientIp) {
        loginAttemptPolicy.recordSuccess(username, clientIp);
    }
}
