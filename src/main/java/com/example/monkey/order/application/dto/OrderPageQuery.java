package com.example.monkey.order.application.dto;

import java.util.List;

public record OrderPageQuery(int page, int size, List<SortOrder> sortOrders) {

    public OrderPageQuery {
        sortOrders = sortOrders == null ? List.of() : List.copyOf(sortOrders);
    }

    public record SortOrder(String property, Direction direction) {
        public enum Direction {
            ASC,
            DESC
        }
    }
}
