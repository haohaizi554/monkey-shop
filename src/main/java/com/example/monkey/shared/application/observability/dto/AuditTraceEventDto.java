package com.example.monkey.shared.application.observability.dto;

import java.time.LocalDateTime;

public record AuditTraceEventDto(
        Long id,
        String eventType,
        String outcome,
        Long actorUserId,
        String actorRole,
        String subjectHash,
        String sourceIp,
        String traceId,
        String detail,
        LocalDateTime createdAt) {}
