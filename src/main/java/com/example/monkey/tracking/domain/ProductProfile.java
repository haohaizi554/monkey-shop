package com.example.monkey.tracking.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ProductProfile(
        Long productId,
        Long categoryId,
        List<String> tagVector,
        long salesCount,
        BigDecimal reviewScore,
        LocalDateTime lastEventAt,
        long version) {

    public ProductProfile {
        tagVector = tagVector == null ? List.of() : List.copyOf(tagVector);
        reviewScore = reviewScore == null ? BigDecimal.ZERO : reviewScore;
        lastEventAt = lastEventAt == null ? LocalDateTime.now() : lastEventAt;
    }
}
