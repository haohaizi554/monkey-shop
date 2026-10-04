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
        int stock,
        int score) {

    public SearchProduct(
            Long productId,
            Long categoryId,
            String name,
            String title,
            String imageUrl,
            BigDecimal originalPrice,
            BigDecimal memberPrice,
            Map<String, Object> attributes,
            int score) {
        this(productId, categoryId, name, title, imageUrl, originalPrice, memberPrice, attributes, 0, score);
    }
}
