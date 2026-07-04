package com.example.monkey.payment.application.dto;

import com.example.monkey.payment.domain.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PaymentCreateRequestDto(
        @NotNull Long orderId,
        @NotNull PaymentMethod method,
        @Size(max = 64) String bankCardNo,
        @Size(max = 16) String totpCode) {}
