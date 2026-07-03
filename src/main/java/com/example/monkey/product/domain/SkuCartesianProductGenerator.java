package com.example.monkey.product.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SkuCartesianProductGenerator {

    private SkuCartesianProductGenerator() {}

    public static List<SkuSpecification> generate(List<SpecificationDimension> dimensions) {
        List<SpecificationDimension> safeDimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        if (safeDimensions.isEmpty()) {
            return List.of();
        }
        List<SkuSpecification> combinations = new ArrayList<>();
        appendCombination(safeDimensions, 0, new LinkedHashMap<>(), combinations);
        return List.copyOf(combinations);
    }

    private static void appendCombination(
            List<SpecificationDimension> dimensions,
            int dimensionIndex,
            Map<String, String> current,
            List<SkuSpecification> output) {
        if (dimensionIndex == dimensions.size()) {
            output.add(new SkuSpecification(current));
            return;
        }
        SpecificationDimension dimension = dimensions.get(dimensionIndex);
        for (String value : dimension.values()) {
            current.put(dimension.name(), value);
            appendCombination(dimensions, dimensionIndex + 1, current, output);
            current.remove(dimension.name());
        }
    }
}
