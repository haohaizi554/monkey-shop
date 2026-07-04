package com.example.monkey.search.domain;

import java.time.LocalDateTime;
import java.util.Map;

public record SearchHistoryEntry(
        Long id,
        Long userId,
        String keyword,
        String normalizedKeyword,
        Long categoryId,
        Map<String, String> filters,
        Long clickedProductId,
        boolean converted,
        int resultCount,
        LocalDateTime createdAt) {}
