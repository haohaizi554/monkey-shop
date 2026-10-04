package com.example.monkey.search.domain;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

public record SearchQuery(
        String keyword,
        Long categoryId,
        Map<String, String> attributes,
        SearchSort sort,
        int page,
        int size,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        boolean inStock) {

    private static final int MAX_PAGE_SIZE = 50;

    public SearchQuery(
            String keyword, Long categoryId, Map<String, String> attributes, SearchSort sort, int page, int size) {
        this(keyword, categoryId, attributes, sort, page, size, null, null, false);
    }

    public SearchQuery {
        keyword = normalizeKeyword(keyword);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        sort = sort == null ? SearchSort.RELEVANCE : sort;
        page = Math.max(0, page);
        size = size <= 0 ? 20 : Math.min(MAX_PAGE_SIZE, size);
        if (minPrice != null && minPrice.signum() < 0) {
            throw new IllegalArgumentException("minPrice must be non-negative");
        }
        if (maxPrice != null && maxPrice.signum() < 0) {
            throw new IllegalArgumentException("maxPrice must be non-negative");
        }
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new IllegalArgumentException("minPrice must not exceed maxPrice");
        }
    }

    public String normalizedKeyword() {
        return normalizeKeyword(keyword);
    }

    private static String normalizeKeyword(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
