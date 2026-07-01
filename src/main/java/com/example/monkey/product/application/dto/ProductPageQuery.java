package com.example.monkey.product.application.dto;

import java.util.List;

public record ProductPageQuery(int page, int size, List<SortOrder> sortOrders) {

    public ProductPageQuery {
        sortOrders = sortOrders == null ? List.of() : List.copyOf(sortOrders);
    }

    public record SortOrder(String property, Direction direction) {
        public enum Direction {
            ASC,
            DESC
        }
    }
}
