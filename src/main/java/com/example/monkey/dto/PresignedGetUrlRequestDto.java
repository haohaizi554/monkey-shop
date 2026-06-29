package com.example.monkey.dto;

import jakarta.validation.constraints.NotBlank;

public record PresignedGetUrlRequestDto(
        @NotBlank(message = "objectKey is required") String objectKey) {}
