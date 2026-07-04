package com.example.monkey.user.application;

import java.util.List;

public record AuthenticatedUserPrincipal(
        Long userId, String role, List<String> authorities, boolean passwordChangeRequired, Long tenantId) {

    private static final long DEFAULT_TENANT_ID = 1L;

    public AuthenticatedUserPrincipal {
        authorities = authorities == null ? List.of() : List.copyOf(authorities);
        tenantId = tenantId == null || tenantId <= 0 ? DEFAULT_TENANT_ID : tenantId;
    }

    public AuthenticatedUserPrincipal(Long userId, String role) {
        this(userId, role, List.of("ROLE_" + role), false);
    }

    public AuthenticatedUserPrincipal(
            Long userId, String role, List<String> authorities, boolean passwordChangeRequired) {
        this(userId, role, authorities, passwordChangeRequired, DEFAULT_TENANT_ID);
    }
}
