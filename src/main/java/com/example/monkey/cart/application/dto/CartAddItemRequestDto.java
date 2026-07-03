package com.example.monkey.cart.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CartAddItemRequestDto(
        @NotNull Long skuId,
        @NotNull Long shopId,
        @Min(1) @Max(999) int quantity,
        boolean selected) {}
