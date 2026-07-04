package com.example.monkey.shared.infrastructure.tenant;

import com.example.monkey.shared.application.tenant.TenantContext;
import java.util.function.Supplier;

public class CurrentTenantIdSupplier implements Supplier<Long> {

    @Override
    public Long get() {
        return TenantContext.currentTenantIdOrDefault();
    }
}
