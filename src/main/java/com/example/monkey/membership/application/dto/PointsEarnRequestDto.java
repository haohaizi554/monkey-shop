package com.example.monkey.membership.application.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record PointsEarnRequestDto(
        Long orderId, @NotNull @DecimalMin("0.01") BigDecimal amount, String referenceKey) {}
