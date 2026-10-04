package com.example.monkey.tenant.application.dto;

import com.example.monkey.tenant.domain.TenantConfigType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record TenantConfigRequestDto(
        @NotNull TenantConfigType configType,
        @Size(max = 64) String provider,
        @NotNull @Size(max = 64) Map<String, String> settings,
        Boolean enabled) {}
