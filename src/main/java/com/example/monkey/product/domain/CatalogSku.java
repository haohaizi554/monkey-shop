package com.example.monkey.product.domain;

public record CatalogSku(
        Long id,
        Long spuId,
        String skuCode,
        SkuSpecification specification,
        ProductPriceBook priceBook,
        boolean active) {
    public CatalogSku {
        if (id == null) {
            throw new IllegalArgumentException("SKU id is required");
        }
        if (spuId == null) {
            throw new IllegalArgumentException("SPU id is required");
        }
        if (skuCode == null || skuCode.isBlank()) {
            throw new IllegalArgumentException("SKU code is required");
        }
        if (specification == null) {
            throw new IllegalArgumentException("SKU specification is required");
        }
        if (priceBook == null) {
            throw new IllegalArgumentException("SKU price book is required");
        }
    }
}
