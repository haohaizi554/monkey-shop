package com.example.monkey.admin.infrastructure;

import com.example.monkey.admin.domain.AdminStatsReader;
import com.example.monkey.admin.domain.AdminStatsReader.TrendGranularity;
import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.order.infrastructure.OrderRepository;
import com.example.monkey.order.infrastructure.OrderRepository.OrderTrendProjection;
import com.example.monkey.shared.infrastructure.observability.VisitLogRepository;
import com.example.monkey.shared.infrastructure.observability.VisitLogRepository.VisitTrendProjection;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class JpaAdminStatsReader implements AdminStatsReader {

    private final OrderRepository orderRepository;
    private final VisitLogRepository visitLogRepository;

    public JpaAdminStatsReader(OrderRepository orderRepository, VisitLogRepository visitLogRepository) {
        this.orderRepository = orderRepository;
        this.visitLogRepository = visitLogRepository;
    }

    @Override
    public Snapshot readStats(
            LocalDateTime startInclusive, LocalDateTime endInclusive, TrendGranularity trendGranularity) {
        return new Snapshot(
                totalGmv(),
                orderRepository.count(),
                orderRepository.countByStatusIn(returnStatuses()),
                readTrendStats(startInclusive, endInclusive, trendGranularity),
                visitLogRepository.count());
    }

    private List<TrendStat> readTrendStats(
            LocalDateTime startInclusive, LocalDateTime endInclusive, TrendGranularity trendGranularity) {
        TrendGranularity resolvedGranularity = trendGranularity == null ? TrendGranularity.DAILY : trendGranularity;
        Map<String, MutableTrendStat> buckets = new LinkedHashMap<>();

        readOrderTrend(startInclusive, endInclusive, resolvedGranularity).forEach(order -> {
            String key =
                    bucketKey(order.getBucketYear(), order.getBucketMonth(), order.getBucketDay(), resolvedGranularity);
            MutableTrendStat bucket = buckets.computeIfAbsent(key, MutableTrendStat::new);
            bucket.addOrders(order.getOrderCount(), order.getGmv());
        });
        readVisitTrend(startInclusive, endInclusive, resolvedGranularity).forEach(visit -> {
            String key =
                    bucketKey(visit.getBucketYear(), visit.getBucketMonth(), visit.getBucketDay(), resolvedGranularity);
            MutableTrendStat bucket = buckets.computeIfAbsent(key, MutableTrendStat::new);
            bucket.addVisits(visit.getVisitCount());
        });

        return buckets.values().stream().map(MutableTrendStat::toTrendStat).toList();
    }

    private List<OrderTrendProjection> readOrderTrend(
            LocalDateTime startInclusive, LocalDateTime endInclusive, TrendGranularity trendGranularity) {
        if (trendGranularity == TrendGranularity.MONTHLY) {
            return orderRepository.findMonthlyOrderTrend(startInclusive, endInclusive, OrderStatus.REFUNDED.label());
        }
        return orderRepository.findDailyOrderTrend(startInclusive, endInclusive, OrderStatus.REFUNDED.label());
    }

    private List<VisitTrendProjection> readVisitTrend(
            LocalDateTime startInclusive, LocalDateTime endInclusive, TrendGranularity trendGranularity) {
        if (trendGranularity == TrendGranularity.MONTHLY) {
            return visitLogRepository.findMonthlyVisitTrend(startInclusive, endInclusive);
        }
        return visitLogRepository.findDailyVisitTrend(startInclusive, endInclusive);
    }

    private BigDecimal totalGmv() {
        BigDecimal total = orderRepository.sumGmvExcludingStatus(OrderStatus.REFUNDED.label());
        return total != null ? total : BigDecimal.ZERO;
    }

    private static List<String> returnStatuses() {
        return List.of(
                OrderStatus.RETURN_REQUESTED.label(),
                OrderStatus.WAITING_RETURN_SHIPMENT.label(),
                OrderStatus.RETURN_SHIPPING.label(),
                OrderStatus.REFUNDED.label());
    }

    private static String bucketKey(Integer year, Integer month, Integer day, TrendGranularity trendGranularity) {
        int safeYear = Objects.requireNonNullElse(year, 0);
        int safeMonth = Objects.requireNonNullElse(month, 0);
        if (trendGranularity == TrendGranularity.MONTHLY) {
            return String.format(Locale.ROOT, "%04d-%02d", safeYear, safeMonth);
        }
        return String.format(Locale.ROOT, "%02d-%02d", safeMonth, Objects.requireNonNullElse(day, 0));
    }

    private static final class MutableTrendStat {
        private final String bucket;
        private long orderCount;
        private BigDecimal gmv = BigDecimal.ZERO;
        private long visitCount;

        private MutableTrendStat(String bucket) {
            this.bucket = bucket;
        }

        private void addOrders(Long count, BigDecimal orderGmv) {
            orderCount += count == null ? 0L : count;
            if (orderGmv != null) {
                gmv = gmv.add(orderGmv);
            }
        }

        private void addVisits(Long count) {
            visitCount += count == null ? 0L : count;
        }

        private TrendStat toTrendStat() {
            return new TrendStat(bucket, orderCount, gmv, visitCount);
        }
    }
}
