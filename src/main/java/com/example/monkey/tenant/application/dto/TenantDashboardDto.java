package com.example.monkey.tenant.application.dto;

import java.math.BigDecimal;
import java.util.List;

public record TenantDashboardDto(
        long activeTenants,
        long expiredTenants,
        long currentMonthOrders,
        BigDecimal currentMonthRevenue,
        List<TenantResponseDto> tenants) {}
