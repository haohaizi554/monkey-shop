package com.example.monkey.user.application;

import java.util.List;

public record AuthenticatedUserPrincipal(
        Long userId, String role, List<String> authorities, boolean passwordChangeRequired) {

    public AuthenticatedUserPrincipal {
        authorities = authorities == null ? List.of() : List.copyOf(authorities);
    }

    public AuthenticatedUserPrincipal(Long userId, String role) {
        this(userId, role, List.of("ROLE_" + role), false);
    }
}
