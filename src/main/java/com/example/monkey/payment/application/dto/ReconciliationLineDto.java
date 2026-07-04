package com.example.monkey.payment.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record ReconciliationLineDto(
        @NotBlank @Size(max = 64) String paymentNo,
        @Size(max = 96) String providerTradeNo,
        @PositiveOrZero BigDecimal amount) {}
