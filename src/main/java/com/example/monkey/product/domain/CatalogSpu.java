package com.example.monkey.product.domain;

import java.util.List;
import java.util.Map;

public record CatalogSpu(
        Long id,
        Long categoryId,
        String name,
        String title,
        ProductStatus status,
        ProductPriceBook priceBook,
        Map<String, Object> attributes,
        String detailJsonLd,
        String supplierPrivateRemark,
        String imageUrl,
        List<CatalogSku> skus) {
    public CatalogSpu {
        if (id == null) {
            throw new IllegalArgumentException("SPU id is required");
        }
        if (categoryId == null) {
            throw new IllegalArgumentException("category id is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("SPU name is required");
        }
        if (priceBook == null) {
            throw new IllegalArgumentException("SPU price book is required");
        }
        status = status == null ? ProductStatus.DRAFT : status;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        skus = skus == null ? List.of() : List.copyOf(skus);
    }

    public CatalogSpu transitionTo(ProductStatus targetStatus) {
        return new CatalogSpu(
                id,
                categoryId,
                name,
                title,
                ProductStateMachine.transition(status, targetStatus),
                priceBook,
                attributes,
                detailJsonLd,
                supplierPrivateRemark,
                imageUrl,
                skus);
    }
}
