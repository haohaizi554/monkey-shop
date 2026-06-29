package com.example.monkey.dto;

import java.math.BigDecimal;

public record MonkeyResponseDto(
        Long id, String name, String breed, BigDecimal price, String description, String imageUrl, Integer stock) {}
