package com.example.monkey.cart.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record CartUpdateItemRequestDto(@Min(1) @Max(999) int quantity) {}
