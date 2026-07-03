package com.example.monkey.product.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record CatalogSpecificationDimensionDto(
        @NotBlank String name, @NotEmpty List<@NotBlank String> values) {}
