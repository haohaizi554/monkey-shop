package com.example.monkey.shared.infrastructure.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.tenant.TenantScoped;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantScopedEntityListenerTest {

    private final TenantScopedEntityListener listener = new TenantScopedEntityListener();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void injectsTenantIntoTenantScopedEntity() {
        TenantContext.setTenantId(500L);
        SampleTenantEntity entity = new SampleTenantEntity();

        listener.injectTenant(entity);

        assertThat(entity.getTenantId()).isEqualTo(500L);
    }

    @Test
    void leavesExistingTenantUntouched() {
        TenantContext.setTenantId(500L);
        SampleTenantEntity entity = new SampleTenantEntity();
        entity.setTenantId(600L);

        listener.injectTenant(entity);

        assertThat(entity.getTenantId()).isEqualTo(600L);
    }

    private static final class SampleTenantEntity implements TenantScoped {
        private Long tenantId;

        @Override
        public Long getTenantId() {
            return tenantId;
        }

        @Override
        public void setTenantId(Long tenantId) {
            this.tenantId = tenantId;
        }
    }
}
