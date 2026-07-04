package com.example.monkey.logistics.application.dto;

import com.example.monkey.logistics.domain.LogisticsCarrier;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record FreightQuoteRequestDto(
        @NotNull LogisticsCarrier carrier,
        String province,
        @NotNull @DecimalMin("0.01") BigDecimal weightKg,
        @Min(1) int itemCount) {}
