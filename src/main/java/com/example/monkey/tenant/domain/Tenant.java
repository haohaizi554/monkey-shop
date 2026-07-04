package com.example.monkey.tenant.domain;

import java.time.LocalDateTime;

public record Tenant(
        Long id,
        String code,
        String name,
        TenantStatus status,
        TenantPlan plan,
        String contactName,
        String contactPhone,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        long version) {

    public Tenant {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("tenant id must be positive");
        }
        code = requireText(code, "tenant code").toLowerCase();
        name = requireText(name, "tenant name");
        status = status == null ? TenantStatus.TRIAL : status;
        plan = plan == null ? TenantPlan.STARTER : plan;
        contactName = contactName == null ? "" : contactName.trim();
        contactPhone = contactPhone == null ? "" : contactPhone.trim();
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
        expiresAt = expiresAt == null ? createdAt.plusDays(30) : expiresAt;
    }

    public Tenant renew(int months) {
        LocalDateTime base = expiresAt.isAfter(LocalDateTime.now()) ? expiresAt : LocalDateTime.now();
        return new Tenant(
                id,
                code,
                name,
                TenantStatus.ACTIVE,
                plan,
                contactName,
                contactPhone,
                createdAt,
                base.plusMonths(Math.max(1, months)),
                version + 1);
    }

    public Tenant downgrade(TenantPlan targetPlan) {
        TenantPlan nextPlan = targetPlan == null ? TenantPlan.STARTER : targetPlan;
        return new Tenant(
                id,
                code,
                name,
                TenantStatus.DOWNGRADED,
                nextPlan,
                contactName,
                contactPhone,
                createdAt,
                expiresAt,
                version + 1);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
