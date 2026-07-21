package com.example.monkey.order.application.dto;

import com.example.monkey.order.domain.OrderStatus;
import java.util.List;
import java.util.stream.Stream;

public record OrderPageQuery(int page, int size, List<SortOrder> sortOrders, List<String> statuses, String keyword) {

    public OrderPageQuery {
        sortOrders = sortOrders == null ? List.of() : List.copyOf(sortOrders);
        statuses = normalizeStatuses(statuses);
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
    }

    public OrderPageQuery(int page, int size, List<SortOrder> sortOrders) {
        this(page, size, sortOrders, List.of(), null);
    }

    private static List<String> normalizeStatuses(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .map(OrderStatus::fromStoredValue)
                .distinct()
                .flatMap(status -> Stream.of(status.name(), status.label()))
                .toList();
    }

    public record SortOrder(String property, Direction direction) {
        public enum Direction {
            ASC,
            DESC
        }
    }
}
