package com.example.monkey.product.domain;

import java.util.List;

public record SpecificationDimension(String name, List<String> values) {
    public SpecificationDimension {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("specification dimension name is required");
        }
        values = values == null ? List.of() : List.copyOf(values);
        if (values.isEmpty() || values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("specification dimension values are required");
        }
    }
}
