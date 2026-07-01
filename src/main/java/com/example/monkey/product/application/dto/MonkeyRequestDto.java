package com.example.monkey.product.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record MonkeyRequestDto(
        Long id,
        @NotBlank(message = "name is required") String name,
        @NotBlank(message = "breed is required") String breed,

        @NotNull(message = "price is required") @Positive(message = "price must be positive") BigDecimal price,

        String description,
        @NotBlank(message = "image url is required") String imageUrl,

        @NotNull(message = "stock is required") @Min(value = 0, message = "stock cannot be negative") Integer stock) {}
