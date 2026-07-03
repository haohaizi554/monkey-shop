package com.example.monkey.product.application.dto;

import com.example.monkey.product.domain.ProductStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record CatalogSpuResponseDto(
        Long id,
        Long categoryId,
        String name,
        String title,
        ProductStatus status,
        BigDecimal originalPrice,
        BigDecimal memberPrice,
        BigDecimal strikePrice,
        Map<String, BigDecimal> regionPrices,
        Map<String, Object> attributes,
        String detailJsonLd,
        String imageUrl,
        List<CatalogSkuResponseDto> skus) {}
