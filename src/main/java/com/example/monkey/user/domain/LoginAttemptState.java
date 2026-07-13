package com.example.monkey.user.domain;

public record LoginAttemptState(boolean captchaRequired, boolean locked, long retryAfterSeconds) {

    public LoginAttemptState {
        retryAfterSeconds = locked ? Math.max(1L, retryAfterSeconds) : 0L;
    }

    public static LoginAttemptState allowed(boolean captchaRequired) {
        return new LoginAttemptState(captchaRequired, false, 0L);
    }

    public static LoginAttemptState locked(boolean captchaRequired, long retryAfterSeconds) {
        return new LoginAttemptState(captchaRequired, true, retryAfterSeconds);
    }
}
