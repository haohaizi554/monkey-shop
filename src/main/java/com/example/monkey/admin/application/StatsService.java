package com.example.monkey.admin.application;

import com.example.monkey.admin.application.dto.StatsResponseDto;
import com.example.monkey.admin.domain.AdminStatsReader;
import com.example.monkey.admin.domain.AdminStatsReader.TrendGranularity;
import com.example.monkey.admin.domain.AdminStatsReader.TrendStat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatsService {

    private final AdminStatsReader adminStatsReader;

    public StatsService(AdminStatsReader adminStatsReader) {
        this.adminStatsReader = adminStatsReader;
    }

    @Transactional(readOnly = true)
    public StatsResponseDto getStats(LocalDate start, LocalDate end) {
        DateRange dateRange = resolveDateRange(start, end);
        LocalDateTime startDt = dateRange.start().atStartOfDay();
        LocalDateTime endDt = dateRange.end().atTime(23, 59, 59);
        boolean groupByMonth = shouldGroupByMonth(dateRange.start(), dateRange.end());

        AdminStatsReader.Snapshot snapshot = adminStatsReader.readStats(
                startDt, endDt, groupByMonth ? TrendGranularity.MONTHLY : TrendGranularity.DAILY);

        Trend trend = buildTrend(dateRange.start(), dateRange.end(), groupByMonth, snapshot.trendStats());
        return StatsDtoAssembler.toResponse(
                snapshot.totalGmv() != null ? snapshot.totalGmv() : BigDecimal.ZERO,
                snapshot.totalOrders(),
                snapshot.totalVisits(),
                returnRate(snapshot.totalOrders(), snapshot.returnOrders()),
                trend.xAxis(),
                trend.seriesOrder(),
                trend.seriesGmv(),
                trend.seriesVisit());
    }

    private static DateRange resolveDateRange(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            LocalDate resolvedEnd = LocalDate.now();
            return new DateRange(resolvedEnd.minusDays(6), resolvedEnd);
        }
        return new DateRange(start, end);
    }

    private static double returnRate(long totalOrders, long returnOrders) {
        if (totalOrders == 0) {
            return 0;
        }
        return (double) returnOrders / totalOrders * 100;
    }

    private static boolean shouldGroupByMonth(LocalDate start, LocalDate end) {
        return ChronoUnit.DAYS.between(start, end) > 60;
    }

    private static Trend buildTrend(LocalDate start, LocalDate end, boolean groupByMonth, List<TrendStat> trendStats) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(groupByMonth ? "yyyy-MM" : "MM-dd");
        List<String> xAxis = new ArrayList<>();
        List<Integer> seriesOrder = new ArrayList<>();
        List<BigDecimal> seriesGmv = new ArrayList<>();
        List<Integer> seriesVisit = new ArrayList<>();
        Map<String, TrendStat> statsByBucket = mergeTrendStats(trendStats);

        LocalDate current = start;
        while (!current.isAfter(end)) {
            String key = current.format(formatter);
            if (!xAxis.contains(key)) {
                xAxis.add(key);
            }
            current = groupByMonth ? current.plusMonths(1) : current.plusDays(1);
        }

        for (String key : xAxis) {
            TrendStat stat = statsByBucket.get(key);
            seriesOrder.add(toSeriesInt(stat == null ? 0L : stat.orderCount()));
            seriesGmv.add(stat == null || stat.gmv() == null ? BigDecimal.ZERO : stat.gmv());
            seriesVisit.add(toSeriesInt(stat == null ? 0L : stat.visitCount()));
        }

        return new Trend(xAxis, seriesOrder, seriesGmv, seriesVisit);
    }

    private static Map<String, TrendStat> mergeTrendStats(List<TrendStat> trendStats) {
        Map<String, TrendStat> merged = new HashMap<>();
        if (trendStats == null) {
            return merged;
        }
        trendStats.stream()
                .filter(stat -> stat.bucket() != null)
                .forEach(stat -> merged.merge(stat.bucket(), normalize(stat), StatsService::merge));
        return merged;
    }

    private static TrendStat normalize(TrendStat stat) {
        return new TrendStat(
                stat.bucket(),
                Math.max(0L, stat.orderCount()),
                stat.gmv() == null ? BigDecimal.ZERO : stat.gmv(),
                Math.max(0L, stat.visitCount()));
    }

    private static TrendStat merge(TrendStat left, TrendStat right) {
        return new TrendStat(
                left.bucket(),
                left.orderCount() + right.orderCount(),
                left.gmv().add(right.gmv()),
                left.visitCount() + right.visitCount());
    }

    private static int toSeriesInt(long value) {
        if (value <= 0L) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private record DateRange(LocalDate start, LocalDate end) {}

    private record Trend(
            List<String> xAxis, List<Integer> seriesOrder, List<BigDecimal> seriesGmv, List<Integer> seriesVisit) {}
}
