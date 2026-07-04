package com.example.monkey.tenant.application.dto;

import com.example.monkey.tenant.domain.TenantConfigType;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record TenantConfigRequestDto(
        @NotNull TenantConfigType configType, String provider, Map<String, String> settings, Boolean enabled) {}
