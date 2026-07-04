package com.example.monkey.tenant.application.dto;

import com.example.monkey.tenant.domain.TenantPlan;
import com.example.monkey.tenant.domain.TenantStatus;
import java.time.LocalDateTime;

public record TenantResponseDto(
        Long id,
        String code,
        String name,
        TenantStatus status,
        TenantPlan plan,
        String contactName,
        String maskedContactPhone,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        long version) {}
