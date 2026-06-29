package com.example.monkey.shared.application.dto;

import java.util.List;

public record PageResponseDto<T>(
        List<T> content, int page, int size, long totalElements, int totalPages, boolean first, boolean last) {

    public static <T> PageResponseDto<T> from(
            List<T> content, int page, int size, long totalElements, int totalPages, boolean first, boolean last) {
        return new PageResponseDto<>(content, page, size, totalElements, totalPages, first, last);
    }
}
