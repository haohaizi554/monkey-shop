package com.example.monkey.product.application.dto;

import java.math.BigDecimal;
import java.util.Map;

public record CatalogSkuResponseDto(
        Long id,
        Long spuId,
        String skuCode,
        Map<String, String> specification,
        BigDecimal originalPrice,
        BigDecimal memberPrice,
        BigDecimal strikePrice,
        Map<String, BigDecimal> regionPrices,
        boolean active) {}
