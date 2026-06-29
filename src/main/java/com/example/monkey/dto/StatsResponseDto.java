package com.example.monkey.dto;

import java.math.BigDecimal;
import java.util.List;

public record StatsResponseDto(
        String totalGmv,
        long totalOrders,
        long totalVisits,
        String returnRate,
        List<String> xAxis,
        List<Integer> seriesOrder,
        List<BigDecimal> seriesGmv,
        List<Integer> seriesVisit) {}
