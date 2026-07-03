package com.example.monkey.cart.domain;

import java.math.BigDecimal;

public record CartSkuSnapshot(
        Long skuId,
        Long spuId,
        Long categoryId,
        String skuCode,
        String productName,
        String productImage,
        BigDecimal salePrice) {
    public CartSkuSnapshot {
        if (skuId == null || spuId == null || salePrice == null) {
            throw new IllegalArgumentException("SKU snapshot is incomplete");
        }
    }
}
