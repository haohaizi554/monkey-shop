package com.example.monkey.payment.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record PaymentRefundRequestDto(
        @NotBlank @Size(max = 64) String paymentNo,
        @Positive BigDecimal amount,
        @Size(max = 255) String reason) {}
