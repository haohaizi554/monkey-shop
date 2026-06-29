package com.example.monkey.domain.observability;

import java.time.LocalDateTime;
import java.util.List;

public interface AuditLogStore {

    void save(AuditEventRecord record);

    List<AuditEventRecord> findFirst50ByTraceId(String traceId);

    long deleteCreatedBefore(LocalDateTime cutoff);

    record AuditEventRecord(
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
}
