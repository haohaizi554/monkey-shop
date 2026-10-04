package com.example.monkey.product.application.dto;

import com.example.monkey.product.domain.CatalogStore;
import com.example.monkey.product.domain.ProductStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record CatalogManagementPageQueryDto(
        @Min(0) Integer page, @Min(1) @Max(100) Integer size, ProductStatus status, String keyword) {

    public CatalogManagementPageQueryDto {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
    }

    public CatalogStore.CatalogPageRequest toRequest() {
        return new CatalogStore.CatalogPageRequest(page, size, status, keyword);
    }
}
