package com.example.monkey.order.domain;

import java.time.LocalDateTime;
import java.util.List;

public record OrderReview(
        Long id,
        Long orderId,
        Long userId,
        Long skuId,
        int rating,
        String content,
        List<String> imageUrls,
        boolean anonymous,
        LocalDateTime createTime) {

    public OrderReview {
        imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
    }
}
