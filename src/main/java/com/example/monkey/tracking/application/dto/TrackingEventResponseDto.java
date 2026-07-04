package com.example.monkey.tracking.application.dto;

import com.example.monkey.tracking.domain.TrackingEventType;
import java.time.LocalDateTime;

public record TrackingEventResponseDto(
        Long id,
        Long userId,
        String sessionId,
        String traceId,
        TrackingEventType eventType,
        String page,
        LocalDateTime occurredAt) {}
