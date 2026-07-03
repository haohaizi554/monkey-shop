package com.example.monkey.product.domain;

import java.util.Map;
import java.util.Set;

public final class ProductStateMachine {

    private static final Map<ProductStatus, Set<ProductStatus>> ALLOWED_TRANSITIONS = Map.of(
            ProductStatus.DRAFT, Set.of(ProductStatus.PENDING_REVIEW, ProductStatus.RECYCLED),
            ProductStatus.PENDING_REVIEW, Set.of(ProductStatus.APPROVED, ProductStatus.DRAFT),
            ProductStatus.APPROVED, Set.of(ProductStatus.LISTED, ProductStatus.RECYCLED),
            ProductStatus.LISTED, Set.of(ProductStatus.UNLISTED),
            ProductStatus.UNLISTED, Set.of(ProductStatus.LISTED, ProductStatus.RECYCLED),
            ProductStatus.RECYCLED, Set.of());

    private ProductStateMachine() {}

    public static ProductStatus transition(ProductStatus current, ProductStatus target) {
        if (!canTransition(current, target)) {
            throw new IllegalStateException("Illegal product status transition: " + current + " -> " + target);
        }
        return target;
    }

    public static boolean canTransition(ProductStatus current, ProductStatus target) {
        if (current == null || target == null) {
            return false;
        }
        return ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(target);
    }
}
