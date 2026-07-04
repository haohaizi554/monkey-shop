package com.example.monkey.search.application.dto;

import java.math.BigDecimal;
import java.util.Map;

public record SearchProductDto(
        Long productId,
        Long categoryId,
        String name,
        String title,
        String imageUrl,
        BigDecimal originalPrice,
        BigDecimal memberPrice,
        Map<String, Object> attributes,
        int score) {}
