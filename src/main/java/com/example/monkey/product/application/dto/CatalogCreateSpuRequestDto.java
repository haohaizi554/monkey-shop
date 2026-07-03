package com.example.monkey.product.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record CatalogCreateSpuRequestDto(
        @NotNull Long categoryId,
        @NotBlank String name,
        @NotBlank String title,
        @NotNull @DecimalMin("0.01") BigDecimal originalPrice,
        BigDecimal memberPrice,
        BigDecimal strikePrice,
        Map<String, BigDecimal> regionPrices,
        Map<String, Object> attributes,
        String detailJsonLd,
        String supplierPrivateRemark,
        String imageUrl,
        @NotEmpty List<@Valid CatalogSpecificationDimensionDto> specifications) {}
