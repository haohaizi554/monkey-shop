package com.example.monkey.user.domain;

public final class RefreshTokenReuseException extends RuntimeException {

    private final Long userId;
    private final String role;

    public RefreshTokenReuseException(Long userId, String role) {
        super("Refresh token reuse detected");
        this.userId = userId;
        this.role = role;
    }

    public Long userId() {
        return userId;
    }

    public String role() {
        return role;
    }
}
