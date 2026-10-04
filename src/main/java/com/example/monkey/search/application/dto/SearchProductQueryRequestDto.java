package com.example.monkey.search.application.dto;

import com.example.monkey.search.domain.SearchQuery;
import com.example.monkey.search.domain.SearchSort;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;

public record SearchProductQueryRequestDto(
        @Size(max = 128) String keyword,
        Long categoryId,
        @Size(max = 64) String attributeKey,
        @Size(max = 128) String attributeValue,
        SearchSort sort,
        @Min(0) Integer page,
        @Min(1) @Max(50) Integer size,
        @DecimalMin(value = "0.0", inclusive = true) BigDecimal minPrice,
        @DecimalMin(value = "0.0", inclusive = true) BigDecimal maxPrice,
        Boolean inStock) {

    public SearchProductQueryRequestDto(
            String keyword,
            Long categoryId,
            String attributeKey,
            String attributeValue,
            SearchSort sort,
            Integer page,
            Integer size) {
        this(keyword, categoryId, attributeKey, attributeValue, sort, page, size, null, null, null);
    }

    @AssertTrue(message = "minPrice must not exceed maxPrice")
    public boolean isPriceRangeValid() {
        return minPrice == null || maxPrice == null || minPrice.compareTo(maxPrice) <= 0;
    }

    public SearchQuery toQuery() {
        if (!isPriceRangeValid()) {
            throw new IllegalArgumentException("minPrice must not exceed maxPrice");
        }
        Map<String, String> attributes = attributeKey == null || attributeKey.isBlank()
                ? Map.of()
                : Map.of(attributeKey.trim(), attributeValue == null ? "" : attributeValue.trim());
        return new SearchQuery(
                keyword,
                categoryId,
                attributes,
                sort == null ? SearchSort.RELEVANCE : sort,
                page == null ? 0 : page,
                size == null ? 20 : size,
                minPrice,
                maxPrice,
                Boolean.TRUE.equals(inStock));
    }
}
