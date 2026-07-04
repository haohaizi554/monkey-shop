package com.example.monkey.order.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;

public record OrderReviewRequestDto(
        Long skuId,
        @Min(1) @Max(5) int rating,
        @Size(max = 1000) String content,
        @Size(max = 6) List<@Size(max = 512) String> imageUrls,
        boolean anonymous) {

    public OrderReviewRequestDto {
        imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
    }
}
