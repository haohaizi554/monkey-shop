package com.example.monkey.shared.infrastructure.observability;

import com.example.monkey.shared.domain.observability.AuditLogStore;
import com.example.monkey.shared.domain.observability.AuditLogStore.AuditEventRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class JpaAuditLogStore implements AuditLogStore {

    private final AuditLogRepository auditLogRepository;

    public JpaAuditLogStore(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    public void save(AuditEventRecord record) {
        auditLogRepository.save(toEntity(record));
    }

    @Override
    public List<AuditEventRecord> findFirst50ByTraceId(String traceId) {
        return auditLogRepository.findTop50ByTraceIdOrderByCreatedAtAsc(traceId).stream()
                .map(JpaAuditLogStore::toRecord)
                .toList();
    }

    @Override
    public long deleteCreatedBefore(LocalDateTime cutoff) {
        return auditLogRepository.deleteByCreatedAtBefore(cutoff);
    }

    private static AuditLog toEntity(AuditEventRecord record) {
        AuditLog auditLog = new AuditLog();
        auditLog.setId(record.id());
        auditLog.setEventType(record.eventType());
        auditLog.setOutcome(record.outcome());
        auditLog.setActorUserId(record.actorUserId());
        auditLog.setActorRole(record.actorRole());
        auditLog.setSubjectHash(record.subjectHash());
        auditLog.setSourceIp(record.sourceIp());
        auditLog.setTraceId(record.traceId());
        auditLog.setDetail(record.detail());
        auditLog.setCreatedAt(record.createdAt());
        return auditLog;
    }

    private static AuditEventRecord toRecord(AuditLog auditLog) {
        return new AuditEventRecord(
                auditLog.getId(),
                auditLog.getEventType(),
                auditLog.getOutcome(),
                auditLog.getActorUserId(),
                auditLog.getActorRole(),
                auditLog.getSubjectHash(),
                auditLog.getSourceIp(),
                auditLog.getTraceId(),
                auditLog.getDetail(),
                auditLog.getCreatedAt());
    }
}
