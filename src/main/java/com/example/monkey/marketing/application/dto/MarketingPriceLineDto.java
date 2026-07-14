package com.example.monkey.marketing.application.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;

public record MarketingPriceLineDto(
        @NotNull Long lineId, @NotNull @DecimalMin("0.01") BigDecimal amount, Long categoryId, Long shopId) {

    public MarketingPriceLineDto {
        amount = amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
    }
}
