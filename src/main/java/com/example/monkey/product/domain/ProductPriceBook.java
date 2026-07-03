package com.example.monkey.product.domain;

import java.math.BigDecimal;
import java.util.Map;

public record ProductPriceBook(
        BigDecimal originalPrice,
        BigDecimal memberPrice,
        BigDecimal strikePrice,
        Map<String, BigDecimal> regionPrices) {
    public ProductPriceBook {
        if (originalPrice == null || originalPrice.signum() <= 0) {
            throw new IllegalArgumentException("original price must be positive");
        }
        if (memberPrice != null && memberPrice.signum() <= 0) {
            throw new IllegalArgumentException("member price must be positive");
        }
        if (strikePrice != null && strikePrice.signum() <= 0) {
            throw new IllegalArgumentException("strike price must be positive");
        }
        regionPrices = regionPrices == null ? Map.of() : Map.copyOf(regionPrices);
        if (regionPrices.values().stream().anyMatch(value -> value == null || value.signum() <= 0)) {
            throw new IllegalArgumentException("region prices must be positive");
        }
    }
}
