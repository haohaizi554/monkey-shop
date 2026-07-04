package com.example.monkey.order.application.dto;

import java.time.LocalDateTime;
import java.util.List;

public record OrderReviewResponseDto(
        Long id,
        Long orderId,
        Long userId,
        Long skuId,
        int rating,
        String content,
        List<String> imageUrls,
        boolean anonymous,
        LocalDateTime createTime) {

    public OrderReviewResponseDto {
        imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
    }
}
