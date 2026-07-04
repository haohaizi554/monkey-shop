package com.example.monkey.shared.application.security;

public record SessionUser(Long id, String role, boolean passwordChangeRequired, Long tenantId) {

    public static final long DEFAULT_TENANT_ID = 1L;

    public SessionUser(Long id, String role) {
        this(id, role, false, DEFAULT_TENANT_ID);
    }

    public SessionUser(Long id, String role, boolean passwordChangeRequired) {
        this(id, role, passwordChangeRequired, DEFAULT_TENANT_ID);
    }

    public SessionUser {
        tenantId = tenantId == null || tenantId <= 0 ? DEFAULT_TENANT_ID : tenantId;
    }
}
