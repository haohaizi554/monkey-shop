package com.example.monkey.shared.application.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void zeroTenantIdNormalizesToPlatformTenant() {
        TenantContext.setTenantId(0L);

        assertThat(TenantContext.currentTenantId()).contains(TenantContext.PLATFORM_TENANT_ID);
    }
}
