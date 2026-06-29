package com.example.monkey.user.application.dto;

import java.util.List;

public record AddressPageQuery(int page, int size, List<SortOrder> sortOrders) {

    public AddressPageQuery {
        sortOrders = sortOrders == null ? List.of() : List.copyOf(sortOrders);
    }

    public record SortOrder(String property, Direction direction) {
        public enum Direction {
            ASC,
            DESC
        }
    }
}
