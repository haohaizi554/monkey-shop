package com.example.monkey.domain.user;

public record SessionUser(Long id, String role, boolean passwordChangeRequired) {

    public SessionUser(Long id, String role) {
        this(id, role, false);
    }
}
