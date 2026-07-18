package com.example.monkey.shared.application.tenant;

public interface TenantAccessGateway {

    boolean isServiceableTenant(Long tenantId);
}
