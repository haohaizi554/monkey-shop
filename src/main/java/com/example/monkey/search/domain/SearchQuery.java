package com.example.monkey.search.domain;

import java.util.Locale;
import java.util.Map;

public record SearchQuery(
        String keyword, Long categoryId, Map<String, String> attributes, SearchSort sort, int page, int size) {

    private static final int MAX_PAGE_SIZE = 50;

    public SearchQuery {
        keyword = normalizeKeyword(keyword);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        sort = sort == null ? SearchSort.RELEVANCE : sort;
        page = Math.max(0, page);
        size = size <= 0 ? 20 : Math.min(MAX_PAGE_SIZE, size);
    }

    public String normalizedKeyword() {
        return normalizeKeyword(keyword);
    }

    private static String normalizeKeyword(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
