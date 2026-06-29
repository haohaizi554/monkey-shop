package com.example.monkey.dto;

import jakarta.validation.constraints.NotBlank;

public record AuditTraceRequestDto(
        @NotBlank(message = "traceId is required") String traceId) {}
