package com.example.monkey.tenant.domain;

import java.math.BigDecimal;

public enum TenantPlan {
    STARTER(new BigDecimal("99.00"), 1000L, new BigDecimal("0.0800")),
    GROWTH(new BigDecimal("399.00"), 10000L, new BigDecimal("0.0300")),
    ENTERPRISE(new BigDecimal("1999.00"), 100000L, new BigDecimal("0.0100"));

    private final BigDecimal monthlyFee;
    private final long includedOrderCount;
    private final BigDecimal extraOrderUnitFee;

    TenantPlan(BigDecimal monthlyFee, long includedOrderCount, BigDecimal extraOrderUnitFee) {
        this.monthlyFee = monthlyFee;
        this.includedOrderCount = includedOrderCount;
        this.extraOrderUnitFee = extraOrderUnitFee;
    }

    public BigDecimal monthlyFee() {
        return monthlyFee;
    }

    public long includedOrderCount() {
        return includedOrderCount;
    }

    public BigDecimal extraOrderUnitFee() {
        return extraOrderUnitFee;
    }

    public BigDecimal usageFee(long orderCount) {
        long extraOrders = Math.max(0L, orderCount - includedOrderCount);
        return extraOrderUnitFee.multiply(BigDecimal.valueOf(extraOrders));
    }
}
