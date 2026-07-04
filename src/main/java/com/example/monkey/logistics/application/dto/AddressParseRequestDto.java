package com.example.monkey.logistics.application.dto;

import jakarta.validation.constraints.NotBlank;

public record AddressParseRequestDto(@NotBlank String text) {}
