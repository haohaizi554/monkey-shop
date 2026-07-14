package com.example.monkey.marketing.application.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public record MarketingPriceAllocationDto(Long lineId, BigDecimal discountAmount, List<String> appliedCoupons) {

    public MarketingPriceAllocationDto {
        discountAmount = (discountAmount == null ? BigDecimal.ZERO : discountAmount).setScale(2, RoundingMode.HALF_UP);
        appliedCoupons = List.copyOf(appliedCoupons == null ? List.of() : appliedCoupons);
    }
}
