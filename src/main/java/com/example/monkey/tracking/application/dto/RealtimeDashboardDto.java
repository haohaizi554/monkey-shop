package com.example.monkey.tracking.application.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record RealtimeDashboardDto(
        long pageViews,
        long uniqueVisitors,
        long orderCount,
        BigDecimal paymentAmount,
        List<FunnelStepDto> funnel,
        LocalDateTime generatedAt,
        int refreshIntervalSeconds) {}
