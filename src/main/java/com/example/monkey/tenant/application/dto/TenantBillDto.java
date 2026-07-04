package com.example.monkey.tenant.application.dto;

import com.example.monkey.tenant.domain.TenantBillStatus;
import com.example.monkey.tenant.domain.TenantPlan;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TenantBillDto(
        Long id,
        Long tenantId,
        String billingMonth,
        TenantPlan plan,
        long orderCount,
        BigDecimal monthlyFee,
        BigDecimal usageFee,
        BigDecimal totalAmount,
        BigDecimal paymentAmount,
        TenantBillStatus status,
        LocalDateTime generatedAt,
        LocalDateTime reconciledAt,
        long version) {}
