package com.example.monkey.tracking.application.dto;

import com.example.monkey.tracking.domain.TrackingEventType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record TrackingEventRequestDto(
        @NotNull TrackingEventType eventType,
        @Size(max = 96) String sessionId,
        @Size(max = 96) String traceId,
        @Size(max = 128) String page,
        @Size(max = 64) String source,
        Long productId,
        Long categoryId,
        Long orderId,
        @DecimalMin("0.00") BigDecimal amount,
        Map<String, String> attributes,
        LocalDateTime occurredAt) {}
