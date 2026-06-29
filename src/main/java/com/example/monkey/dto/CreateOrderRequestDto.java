package com.example.monkey.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateOrderRequestDto(
        @NotNull(message = "monkey id is required") @Positive(message = "monkey id must be positive") Long monkeyId,

        @NotNull(message = "address id is required") @Positive(message = "address id must be positive") Long addressId) {}
