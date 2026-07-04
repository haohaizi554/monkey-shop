package com.example.monkey.logistics.application.dto;

import com.example.monkey.logistics.domain.LogisticsCarrier;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ShipmentCreateRequestDto(
        @NotNull Long orderId,
        @NotNull LogisticsCarrier carrier,
        String recipientPhone,
        String addressText,
        String province,
        String city,
        String district,
        String detail,
        @NotNull @DecimalMin("0.01") BigDecimal weightKg,
        @Min(1) int itemCount) {}
