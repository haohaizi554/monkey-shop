package com.example.monkey.search.application.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SearchConversionRequestDto(
        @Size(max = 128) String keyword,
        @NotNull Long productId,
        @Size(max = 64) String source) {}
