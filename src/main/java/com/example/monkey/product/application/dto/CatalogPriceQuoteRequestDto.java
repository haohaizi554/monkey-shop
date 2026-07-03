package com.example.monkey.product.application.dto;

public record CatalogPriceQuoteRequestDto(String identity, String region) {
    public CatalogPriceQuoteRequestDto {
        identity = identity == null || identity.isBlank() ? "ANONYMOUS" : identity;
        region = region == null ? "" : region;
    }
}
