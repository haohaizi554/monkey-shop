package com.example.monkey.inventory.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record InventoryReserveRequestDto(
        @NotNull Long skuId,
        Long warehouseId,
        String province,
        Long orderId,
        @Min(1) int quantity,
        @NotBlank String reservationKey) {}
