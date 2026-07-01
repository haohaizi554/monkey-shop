package com.example.monkey.admin.application;

import com.example.monkey.admin.application.dto.StatsResponseDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

public final class StatsDtoAssembler {

    private StatsDtoAssembler() {}

    public static StatsResponseDto toResponse(
            BigDecimal totalGmv,
            long totalOrders,
            long totalVisits,
            double returnRate,
            List<String> xAxis,
            List<Integer> seriesOrder,
            List<BigDecimal> seriesGmv,
            List<Integer> seriesVisit) {
        return new StatsResponseDto(
                formatDecimal(totalGmv),
                totalOrders,
                totalVisits,
                formatPercent(returnRate),
                xAxis,
                seriesOrder,
                seriesGmv,
                seriesVisit);
    }

    private static String formatDecimal(BigDecimal value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
