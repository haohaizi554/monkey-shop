package com.example.monkey.admin.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface AdminStatsReader {
    Snapshot readStats(LocalDateTime startInclusive, LocalDateTime endInclusive, TrendGranularity trendGranularity);

    record Snapshot(
            BigDecimal totalGmv, long totalOrders, long returnOrders, List<TrendStat> trendStats, long totalVisits) {}

    enum TrendGranularity {
        DAILY,
        MONTHLY
    }

    record TrendStat(String bucket, long orderCount, BigDecimal gmv, long visitCount) {}
}
