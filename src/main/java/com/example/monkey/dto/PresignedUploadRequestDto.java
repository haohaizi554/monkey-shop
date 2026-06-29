package com.example.monkey.dto;

import jakarta.validation.constraints.NotBlank;

public record PresignedUploadRequestDto(
        @NotBlank(message = "upload type is required") String type,
        @NotBlank(message = "content type is required") String contentType) {}
