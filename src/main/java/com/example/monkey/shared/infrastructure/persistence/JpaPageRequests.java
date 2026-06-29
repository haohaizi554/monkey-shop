package com.example.monkey.shared.infrastructure.persistence;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public final class JpaPageRequests {

    private static final int MIN_PAGE = 0;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;

    private JpaPageRequests() {}

    public static PageRequest bounded(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(MIN_PAGE, page), Math.clamp(size, MIN_SIZE, MAX_SIZE), sort);
    }
}
