package com.example.monkey.product.interfaces.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record MonkeyPageRequestDto(
        @Size(max = 128) String keyword,
        @DecimalMin("0.00") BigDecimal minPrice,
        @DecimalMin("0.00") BigDecimal maxPrice,
        Boolean inStock) {}
