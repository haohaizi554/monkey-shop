package com.example.monkey.product.application.dto;

import java.math.BigDecimal;

public record MonkeyResponseDto(
        Long id, String name, String breed, BigDecimal price, String description, String imageUrl, Integer stock) {}
