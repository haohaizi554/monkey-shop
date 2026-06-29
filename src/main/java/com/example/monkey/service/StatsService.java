package com.example.monkey.service;

import com.example.monkey.assembler.StatsDtoAssembler;
import com.example.monkey.domain.admin.AdminStatsReader;
import com.example.monkey.domain.admin.AdminStatsReader.OrderStat;
import com.example.monkey.domain.admin.AdminStatsReader.VisitStat;
import com.example.monkey.domain.order.OrderStatus;
import com.example.monkey.dto.StatsResponseDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

        AdminStatsReader.Snapshot snapshot = adminStatsReader.readStats(startDt, endDt);
        List<OrderStat> orders = snapshot.orders();
        List<OrderStat> rangeOrders = orders.stream()
                .filter(order -> order.createTime() != null)
                .filter(order -> !order.createTime().isBefore(startDt)
                        && !order.createTime().isAfter(endDt))
                .toList();

        Trend trend = buildTrend(dateRange.start(), dateRange.end(), rangeOrders, snapshot.rangeVisits());
        return StatsDtoAssembler.toResponse(
                totalGmv(orders),
                orders.size(),
                snapshot.totalVisits(),
                returnRate(orders),
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

    private static BigDecimal totalGmv(List<OrderStat> orders) {
        return orders.stream()
                .filter(order -> !isRefunded(order))
                .map(OrderStat::price)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static double returnRate(List<OrderStat> orders) {
        if (orders.isEmpty()) {
            return 0;
        }
        long returnCount = orders.stream().filter(StatsService::isReturnRelated).count();
        return (double) returnCount / orders.size() * 100;
    }

    private static Trend buildTrend(
            LocalDate start, LocalDate end, List<OrderStat> rangeOrders, List<VisitStat> rangeVisits) {
        boolean groupByMonth = ChronoUnit.DAYS.between(start, end) > 60;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(groupByMonth ? "yyyy-MM" : "MM-dd");
        List<String> xAxis = new ArrayList<>();
        List<Integer> seriesOrder = new ArrayList<>();
        List<BigDecimal> seriesGmv = new ArrayList<>();
        List<Integer> seriesVisit = new ArrayList<>();

        LocalDate current = start;
        while (!current.isAfter(end)) {
            String key = current.format(formatter);
            if (!xAxis.contains(key)) {
                xAxis.add(key);
            }
            current = groupByMonth ? current.plusMonths(1) : current.plusDays(1);
        }

        for (String key : xAxis) {
            long orderCount = rangeOrders.stream()
                    .filter(order -> order.createTime().format(formatter).equals(key))
                    .count();
            BigDecimal gmv = rangeOrders.stream()
                    .filter(order -> order.createTime().format(formatter).equals(key))
                    .filter(order -> !isRefunded(order))
                    .map(OrderStat::price)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long visitCount = rangeVisits.stream()
                    .filter(visit -> visit.visitTime() != null)
                    .filter(visit -> visit.visitTime().format(formatter).equals(key))
                    .count();
            seriesOrder.add((int) orderCount);
            seriesGmv.add(gmv);
            seriesVisit.add((int) visitCount);
        }

        return new Trend(xAxis, seriesOrder, seriesGmv, seriesVisit);
    }

    private static boolean isRefunded(OrderStat order) {
        return OrderStatus.REFUNDED.matches(order.status());
    }

    private static boolean isReturnRelated(OrderStat order) {
        return OrderStatus.RETURN_REQUESTED.matches(order.status())
                || OrderStatus.WAITING_RETURN_SHIPMENT.matches(order.status())
                || OrderStatus.RETURN_SHIPPING.matches(order.status())
                || OrderStatus.REFUNDED.matches(order.status());
    }

    private record DateRange(LocalDate start, LocalDate end) {}

    private record Trend(
            List<String> xAxis, List<Integer> seriesOrder, List<BigDecimal> seriesGmv, List<Integer> seriesVisit) {}
}
