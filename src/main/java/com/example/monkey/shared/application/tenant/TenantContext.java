package com.example.monkey.shared.application.tenant;

import java.util.Optional;

public final class TenantContext {

    public static final long PLATFORM_TENANT_ID = 1L;

    private static final ThreadLocal<Long> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static void setTenantId(Long tenantId) {
        CURRENT_TENANT.set(normalize(tenantId));
    }

    public static Optional<Long> currentTenantId() {
        return Optional.ofNullable(CURRENT_TENANT.get());
    }

    public static long currentTenantIdOrDefault() {
        return currentTenantId().orElse(PLATFORM_TENANT_ID);
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }

    private static long normalize(Long tenantId) {
        return tenantId == null || tenantId <= 0 ? PLATFORM_TENANT_ID : tenantId;
    }
}
