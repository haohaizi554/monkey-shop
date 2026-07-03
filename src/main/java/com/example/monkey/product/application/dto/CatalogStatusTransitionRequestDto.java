package com.example.monkey.product.application.dto;

import com.example.monkey.product.domain.ProductStatus;
import jakarta.validation.constraints.NotNull;

public record CatalogStatusTransitionRequestDto(@NotNull ProductStatus targetStatus) {}
