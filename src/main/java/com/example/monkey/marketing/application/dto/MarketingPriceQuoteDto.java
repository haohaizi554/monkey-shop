package com.example.monkey.marketing.application.dto;

import java.math.BigDecimal;
import java.util.List;

public record MarketingPriceQuoteDto(
        BigDecimal originalAmount,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        List<String> appliedCoupons,
        List<MarketingPriceAllocationDto> allocations) {

    public MarketingPriceQuoteDto(
            BigDecimal originalAmount,
            BigDecimal discountAmount,
            BigDecimal payableAmount,
            List<String> appliedCoupons) {
        this(originalAmount, discountAmount, payableAmount, appliedCoupons, List.of());
    }

    public MarketingPriceQuoteDto {
        appliedCoupons = List.copyOf(appliedCoupons == null ? List.of() : appliedCoupons);
        allocations = List.copyOf(allocations == null ? List.of() : allocations);
    }
}
