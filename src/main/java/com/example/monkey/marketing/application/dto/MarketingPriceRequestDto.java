package com.example.monkey.marketing.application.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public record MarketingPriceRequestDto(
        @NotNull @DecimalMin("0.01") BigDecimal orderAmount,
        Long userId,
        Long categoryId,
        Long shopId,
        List<String> couponCodes) {}
