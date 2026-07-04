package com.example.monkey.tracking.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record TrackingEvent(
        Long id,
        Long userId,
        String sessionId,
        String traceId,
        TrackingEventType eventType,
        String page,
        String source,
        Long productId,
        Long categoryId,
        Long orderId,
        BigDecimal amount,
        Map<String, String> attributes,
        LocalDateTime occurredAt) {

    public TrackingEvent {
        sessionId = sessionId == null || sessionId.isBlank() ? "anonymous" : sessionId.trim();
        page = page == null || page.isBlank() ? "unknown" : page.trim();
        source = source == null || source.isBlank() ? "web" : source.trim();
        eventType = eventType == null ? TrackingEventType.CLICK : eventType;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        occurredAt = occurredAt == null ? LocalDateTime.now() : occurredAt;
    }
}
