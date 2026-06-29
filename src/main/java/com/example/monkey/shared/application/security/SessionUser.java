package com.example.monkey.shared.application.security;

public record SessionUser(Long id, String role, boolean passwordChangeRequired) {

    public SessionUser(Long id, String role) {
        this(id, role, false);
    }
}
