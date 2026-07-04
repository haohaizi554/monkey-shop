package com.example.monkey.search.domain;

import java.math.BigDecimal;
import java.util.Map;

public record SearchProduct(
        Long productId,
        Long categoryId,
        String name,
        String title,
        String imageUrl,
        BigDecimal originalPrice,
        BigDecimal memberPrice,
        Map<String, Object> attributes,
        int score) {}
