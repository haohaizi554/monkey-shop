package com.example.monkey.tracking.application.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ProductProfileDto(
        Long productId,
        Long categoryId,
        List<String> tagVector,
        long salesCount,
        BigDecimal reviewScore,
        LocalDateTime lastEventAt,
        long version) {}
