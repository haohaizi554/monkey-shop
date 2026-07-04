package com.example.monkey.shared.infrastructure.tenant;

import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.tenant.TenantScoped;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.lang.reflect.Method;

public class TenantScopedEntityListener {

    @PrePersist
    @PreUpdate
    public void injectTenant(Object entity) {
        if (entity == null) {
            return;
        }
        if (entity instanceof TenantScoped tenantScoped) {
            if (tenantScoped.getTenantId() == null || tenantScoped.getTenantId() <= 0) {
                tenantScoped.setTenantId(TenantContext.currentTenantIdOrDefault());
            }
            return;
        }
        injectByConvention(entity);
    }

    private static void injectByConvention(Object entity) {
        try {
            Method getter = entity.getClass().getMethod("getTenantId");
            Object current = getter.invoke(entity);
            if (current instanceof Long value && value > 0) {
                return;
            }
            Method setter = entity.getClass().getMethod("setTenantId", Long.class);
            setter.invoke(entity, TenantContext.currentTenantIdOrDefault());
        } catch (ReflectiveOperationException ignored) {
            // Non tenant-scoped entities are deliberately ignored.
        }
    }
}
