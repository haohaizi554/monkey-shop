package com.example.monkey.search.application.dto;

import java.util.List;

public record SearchPageDto(
        List<SearchProductDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {}
