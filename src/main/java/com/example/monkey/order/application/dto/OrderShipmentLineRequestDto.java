package com.example.monkey.order.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record OrderShipmentLineRequestDto(
        @NotNull Long skuId,
        String productName,
        @Min(1) int quantity,
        @Min(1) int orderedQuantity) {}
