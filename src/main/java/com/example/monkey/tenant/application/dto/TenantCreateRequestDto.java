package com.example.monkey.tenant.application.dto;

import com.example.monkey.tenant.domain.TenantPlan;
import jakarta.validation.constraints.NotBlank;

public record TenantCreateRequestDto(
        @NotBlank String code,
        @NotBlank String name,
        TenantPlan plan,
        String contactName,
        String contactPhone,
        Integer months) {}
