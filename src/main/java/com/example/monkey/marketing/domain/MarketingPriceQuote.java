package com.example.monkey.marketing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public record MarketingPriceQuote(
        BigDecimal originalAmount, BigDecimal discountAmount, BigDecimal payableAmount, List<String> appliedCoupons) {

    public MarketingPriceQuote {
        originalAmount = scale(originalAmount);
        discountAmount = scale(discountAmount);
        payableAmount = scale(payableAmount);
        appliedCoupons = List.copyOf(appliedCoupons == null ? List.of() : appliedCoupons);
    }

    private static BigDecimal scale(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
    }
}
