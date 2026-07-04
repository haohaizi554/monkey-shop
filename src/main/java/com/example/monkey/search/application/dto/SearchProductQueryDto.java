package com.example.monkey.search.application.dto;

import com.example.monkey.search.domain.SearchQuery;
import com.example.monkey.search.domain.SearchSort;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record SearchProductQueryDto(
        @Size(max = 128) String keyword,
        Long categoryId,
        @Size(max = 64) String attributeKey,
        @Size(max = 128) String attributeValue,
        SearchSort sort,
        @Min(0) Integer page,
        @Min(1) @Max(50) Integer size) {

    public SearchQuery toQuery() {
        Map<String, String> attributes = attributeKey == null || attributeKey.isBlank()
                ? Map.of()
                : Map.of(attributeKey.trim(), attributeValue == null ? "" : attributeValue.trim());
        return new SearchQuery(
                keyword,
                categoryId,
                attributes,
                sort == null ? SearchSort.RELEVANCE : sort,
                page == null ? 0 : page,
                size == null ? 20 : size);
    }
}
