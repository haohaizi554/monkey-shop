package com.example.monkey.domain.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface AdminStatsReader {
    Snapshot readStats(LocalDateTime startInclusive, LocalDateTime endInclusive);

    record Snapshot(List<OrderStat> orders, List<VisitStat> rangeVisits, long totalVisits) {}

    record OrderStat(LocalDateTime createTime, BigDecimal price, String status) {}

    record VisitStat(LocalDateTime visitTime) {}
}
