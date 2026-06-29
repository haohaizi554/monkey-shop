package com.example.monkey.shared.infrastructure.persistence;

import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;

public final class JpaSorts {

    private JpaSorts() {}

    public static Optional<Sort.Order> allowedOrder(
            String property, Sort.Direction direction, Set<String> allowedProperties) {
        if (!StringUtils.hasText(property) || !allowedProperties.contains(property)) {
            return Optional.empty();
        }
        return Optional.of(new Sort.Order(direction, property));
    }
}
