package com.example.monkey.tracking.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record RealtimeDashboard(
        long pageViews,
        long uniqueVisitors,
        long orderCount,
        BigDecimal paymentAmount,
        List<FunnelStep> funnel,
        LocalDateTime generatedAt,
        int refreshIntervalSeconds) {

    public RealtimeDashboard {
        paymentAmount = paymentAmount == null ? BigDecimal.ZERO : paymentAmount;
        funnel = funnel == null ? List.of() : List.copyOf(funnel);
        generatedAt = generatedAt == null ? LocalDateTime.now() : generatedAt;
    }
}
