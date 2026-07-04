package com.example.monkey.membership.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MemberCollection(
        Long id,
        Long userId,
        Long productId,
        String productName,
        String productImage,
        BigDecimal lastPrice,
        BigDecimal targetPrice,
        boolean priceDropNotified,
        long version,
        LocalDateTime createTime,
        LocalDateTime updateTime) {

    public boolean priceDropped(BigDecimal currentPrice) {
        if (targetPrice == null || currentPrice == null || priceDropNotified) {
            return false;
        }
        return currentPrice.compareTo(targetPrice) <= 0 && currentPrice.compareTo(lastPrice) < 0;
    }

    public MemberCollection refreshPrice(BigDecimal currentPrice, boolean notified, LocalDateTime now) {
        return new MemberCollection(
                id,
                userId,
                productId,
                productName,
                productImage,
                currentPrice == null ? lastPrice : currentPrice,
                targetPrice,
                notified || priceDropNotified,
                version,
                createTime,
                now);
    }
}
