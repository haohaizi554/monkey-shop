package com.example.monkey.shared.infrastructure.tenant;

import com.example.monkey.shared.domain.tenant.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

@MappedSuperclass
@EntityListeners(TenantScopedEntityListener.class)
@FilterDef(
        name = TenantScopedJpaEntity.TENANT_FILTER,
        parameters =
                @ParamDef(
                        name = TenantScopedJpaEntity.TENANT_ID_PARAMETER,
                        type = Long.class,
                        resolver = CurrentTenantIdSupplier.class),
        autoEnabled = true,
        applyToLoadByKey = true)
@Filter(name = TenantScopedJpaEntity.TENANT_FILTER, condition = "tenant_id = :tenantId")
public abstract class TenantScopedJpaEntity implements TenantScoped {

    public static final String TENANT_FILTER = "tenantFilter";
    public static final String TENANT_ID_PARAMETER = "tenantId";

    @Column(name = "tenant_id", nullable = false)
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
