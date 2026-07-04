package com.example.monkey.payment.application.dto;

import com.example.monkey.payment.domain.PaymentMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record PaymentCallbackRequestDto(
        @NotNull PaymentMethod provider,
        @NotBlank @Size(max = 64) String paymentNo,
        @NotBlank @Size(max = 128) String callbackId,
        @NotBlank @Size(max = 96) String providerTradeNo,
        @Positive BigDecimal amount,
        @NotBlank @Size(max = 32) String status,
        @NotBlank @Size(max = 128) String signature) {}
