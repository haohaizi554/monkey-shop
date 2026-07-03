package com.example.monkey.product.application.dto;

import java.math.BigDecimal;

public record CatalogPriceQuoteDto(Long spuId, BigDecimal salePrice, BigDecimal strikePrice, String strategy) {}
