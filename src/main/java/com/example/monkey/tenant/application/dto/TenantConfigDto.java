package com.example.monkey.tenant.application.dto;

import com.example.monkey.tenant.domain.TenantConfigType;
import java.time.LocalDateTime;
import java.util.Map;

public record TenantConfigDto(
        Long id,
        Long tenantId,
        TenantConfigType configType,
        String provider,
        Map<String, String> settings,
        boolean enabled,
        LocalDateTime updatedAt,
        long version) {}
