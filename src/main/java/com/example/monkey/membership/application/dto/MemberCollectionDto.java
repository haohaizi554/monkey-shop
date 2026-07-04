package com.example.monkey.membership.application.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MemberCollectionDto(
        Long id,
        Long productId,
        String productName,
        String productImage,
        BigDecimal lastPrice,
        BigDecimal targetPrice,
        boolean priceDropNotified,
        LocalDateTime createTime,
        LocalDateTime updateTime) {}
