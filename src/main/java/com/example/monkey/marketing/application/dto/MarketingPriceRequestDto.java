package com.example.monkey.marketing.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public record MarketingPriceRequestDto(
        @NotNull @DecimalMin("0.01") BigDecimal orderAmount,
        Long userId,
        Long categoryId,
        Long shopId,
        List<String> couponCodes,
        List<@Valid MarketingPriceLineDto> lines) {

    public MarketingPriceRequestDto(
            BigDecimal orderAmount, Long userId, Long categoryId, Long shopId, List<String> couponCodes) {
        this(orderAmount, userId, categoryId, shopId, couponCodes, List.of());
    }

    public MarketingPriceRequestDto {
        couponCodes = List.copyOf(couponCodes == null ? List.of() : couponCodes);
        lines = List.copyOf(lines == null ? List.of() : lines);
    }

    public MarketingPriceRequestDto withUserId(Long newUserId) {
        return new MarketingPriceRequestDto(orderAmount, newUserId, categoryId, shopId, couponCodes, lines);
    }
}
