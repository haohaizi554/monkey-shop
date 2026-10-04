package com.example.monkey.tenant.domain;

import java.util.List;

@FunctionalInterface
public interface ActiveTenantReader {

    List<Long> findActiveTenantIds();

    /**
     * Returns every tenant whose persisted data is still retained. This population is intentionally
     * broader than serviceable tenants: suspended and expired tenants can still own data that must be
     * included in compliance and destructive-cleanup decisions.
     *
     * <p>The default keeps existing lightweight readers and tests source-compatible; the JPA reader
     * overrides it with the repository's complete retained-tenant query.
     */
    default List<Long> findRetainedTenantIds() {
        return findActiveTenantIds();
    }
}
