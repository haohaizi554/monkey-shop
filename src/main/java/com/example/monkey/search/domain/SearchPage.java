package com.example.monkey.search.domain;

import java.util.List;

public record SearchPage(List<SearchProduct> content, int page, int size, long totalElements) {

    public int totalPages() {
        if (size <= 0) {
            return 1;
        }
        return (int) Math.max(1, Math.ceil((double) totalElements / (double) size));
    }

    public boolean first() {
        return page <= 0;
    }

    public boolean last() {
        return page + 1 >= totalPages();
    }
}
