package com.example.monkey.security;

public record SessionUser(Long id, String role) {

    public boolean isAdmin() {
        return SessionIdentity.ROLE_ADMIN.equals(role);
    }
}
