package com.example.monkey.membership.application.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CollectionRequestDto(@NotNull Long productId, BigDecimal targetPrice) {}
