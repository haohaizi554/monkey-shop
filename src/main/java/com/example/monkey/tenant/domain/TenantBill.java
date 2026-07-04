package com.example.monkey.tenant.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;

public record TenantBill(
        Long id,
        Long tenantId,
        YearMonth billingMonth,
        TenantPlan plan,
        long orderCount,
        BigDecimal monthlyFee,
        BigDecimal usageFee,
        BigDecimal totalAmount,
        BigDecimal paymentAmount,
        TenantBillStatus status,
        LocalDateTime generatedAt,
        LocalDateTime reconciledAt,
        long version) {

    public TenantBill {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("tenant id is required");
        }
        billingMonth = billingMonth == null ? YearMonth.now() : billingMonth;
        plan = plan == null ? TenantPlan.STARTER : plan;
        monthlyFee = money(monthlyFee);
        usageFee = money(usageFee);
        totalAmount = money(totalAmount == null ? monthlyFee.add(usageFee) : totalAmount);
        paymentAmount = money(paymentAmount);
        status = status == null ? TenantBillStatus.GENERATED : status;
        generatedAt = generatedAt == null ? LocalDateTime.now() : generatedAt;
    }

    public static TenantBill generate(
            Long id, Tenant tenant, YearMonth month, long orderCount, BigDecimal paymentAmount) {
        BigDecimal usageFee = tenant.plan().usageFee(orderCount);
        BigDecimal monthlyFee = tenant.plan().monthlyFee();
        return new TenantBill(
                id,
                tenant.id(),
                month,
                tenant.plan(),
                orderCount,
                monthlyFee,
                usageFee,
                monthlyFee.add(usageFee),
                paymentAmount,
                TenantBillStatus.GENERATED,
                LocalDateTime.now(),
                null,
                0L);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
