package com.example.monkey.shared.domain.tenant;

public interface TenantScoped {

    Long getTenantId();

    void setTenantId(Long tenantId);
}
