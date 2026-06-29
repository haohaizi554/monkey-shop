package com.example.monkey.dto;

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
