package com.example.monkey.product.application.dto;

import java.math.BigDecimal;
import java.util.List;

public record ProductPageQuery(
        int page,
        int size,
        List<SortOrder> sortOrders,
        String keyword,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        Boolean inStock) {

    public ProductPageQuery {
        sortOrders = sortOrders == null ? List.of() : List.copyOf(sortOrders);
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
    }

    public ProductPageQuery(int page, int size, List<SortOrder> sortOrders) {
        this(page, size, sortOrders, null, null, null, null);
    }

    public record SortOrder(String property, Direction direction) {
        public enum Direction {
            ASC,
            DESC
        }
    }
}
