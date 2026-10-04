package com.example.monkey.product.application.dto;

public record CatalogPriceQuoteRequestDto(String region) {
    public CatalogPriceQuoteRequestDto {
        region = region == null ? "" : region;
    }
}
