package com.example.monkey.tenant.domain;

import java.math.BigDecimal;

public enum TenantPlan {
    STARTER(BigDecimal.valueOf(9900, 2), 1000L, BigDecimal.valueOf(800, 4)),
    GROWTH(BigDecimal.valueOf(39900, 2), 10000L, BigDecimal.valueOf(300, 4)),
    ENTERPRISE(BigDecimal.valueOf(199900, 2), 100000L, BigDecimal.valueOf(100, 4));

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
