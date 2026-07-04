package com.example.monkey.tenant.domain;

import java.math.BigDecimal;
import java.util.List;

public record TenantDashboard(
        long activeTenants,
        long expiredTenants,
        long currentMonthOrders,
        BigDecimal currentMonthRevenue,
        List<Tenant> tenants) {

    public TenantDashboard {
        currentMonthRevenue = currentMonthRevenue == null ? BigDecimal.ZERO : currentMonthRevenue;
        tenants = tenants == null ? List.of() : List.copyOf(tenants);
    }
}
