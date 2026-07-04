package com.example.monkey.membership.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PriceDropEvent(
        Long id,
        Long collectionId,
        Long userId,
        Long productId,
        BigDecimal oldPrice,
        BigDecimal newPrice,
        LocalDateTime notifiedAt) {}
