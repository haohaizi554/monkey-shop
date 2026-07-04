package com.example.monkey.tenant.domain;

import java.time.LocalDateTime;
import java.util.Map;

public record TenantConfig(
        Long id,
        Long tenantId,
        TenantConfigType configType,
        String provider,
        Map<String, String> settings,
        boolean enabled,
        LocalDateTime updatedAt,
        long version) {

    public TenantConfig {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("tenant id is required");
        }
        configType = configType == null ? TenantConfigType.MARKETING : configType;
        provider = provider == null || provider.isBlank() ? "default" : provider.trim();
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        updatedAt = updatedAt == null ? LocalDateTime.now() : updatedAt;
    }
}
