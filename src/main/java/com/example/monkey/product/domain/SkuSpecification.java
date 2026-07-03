package com.example.monkey.product.domain;

import java.util.Map;
import java.util.TreeMap;

public record SkuSpecification(Map<String, String> values) {
    public SkuSpecification {
        values = values == null ? Map.of() : Map.copyOf(new TreeMap<>(values));
        if (values.isEmpty()) {
            throw new IllegalArgumentException("SKU specification is required");
        }
    }

    public String codeSuffix() {
        return String.join(
                "-",
                values.entrySet().stream()
                        .map(entry -> slug(entry.getKey()) + "-" + slug(entry.getValue()))
                        .toList());
    }

    private static String slug(String value) {
        return value.toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-").replaceAll("(^-|-$)", "");
    }
}
