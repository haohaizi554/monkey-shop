package com.example.monkey.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.domain.observability.AuditLogStore.AuditEventRecord;
import com.example.monkey.entity.AuditLog;
import com.example.monkey.repository.AuditLogRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaAuditLogStoreTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Test
    void savesAuditEventRecordAsEntity() {
        JpaAuditLogStore auditLogStore = new JpaAuditLogStore(auditLogRepository);
        AuditEventRecord record = new AuditEventRecord(
                null,
                "LOGIN_FAILURE",
                "FAILURE",
                7L,
                "USER",
                "hash",
                "203.0.113.20",
                "trace-1",
                "result=failure",
                LocalDateTime.parse("2026-06-29T08:00:00"));

        auditLogStore.save(record);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo("LOGIN_FAILURE");
        assertThat(saved.getOutcome()).isEqualTo("FAILURE");
        assertThat(saved.getActorUserId()).isEqualTo(7L);
        assertThat(saved.getActorRole()).isEqualTo("USER");
        assertThat(saved.getSubjectHash()).isEqualTo("hash");
        assertThat(saved.getSourceIp()).isEqualTo("203.0.113.20");
        assertThat(saved.getTraceId()).isEqualTo("trace-1");
        assertThat(saved.getDetail()).isEqualTo("result=failure");
        assertThat(saved.getCreatedAt()).isEqualTo(LocalDateTime.parse("2026-06-29T08:00:00"));
    }

    @Test
    void readsFirstFiftyAuditEventsByTraceId() {
        JpaAuditLogStore auditLogStore = new JpaAuditLogStore(auditLogRepository);
        AuditLog auditLog = new AuditLog();
        auditLog.setId(11L);
        auditLog.setEventType("LOGIN_SUCCESS");
        auditLog.setOutcome("SUCCESS");
        auditLog.setActorUserId(9L);
        auditLog.setActorRole("ADMIN");
        auditLog.setSubjectHash("subject-hash");
        auditLog.setSourceIp("203.0.113.21");
        auditLog.setTraceId("trace-2");
        auditLog.setDetail("result=success");
        auditLog.setCreatedAt(LocalDateTime.parse("2026-06-29T09:00:00"));
        when(auditLogRepository.findTop50ByTraceIdOrderByCreatedAtAsc("trace-2"))
                .thenReturn(List.of(auditLog));

        List<AuditEventRecord> records = auditLogStore.findFirst50ByTraceId("trace-2");

        assertThat(records)
                .containsExactly(new AuditEventRecord(
                        11L,
                        "LOGIN_SUCCESS",
                        "SUCCESS",
                        9L,
                        "ADMIN",
                        "subject-hash",
                        "203.0.113.21",
                        "trace-2",
                        "result=success",
                        LocalDateTime.parse("2026-06-29T09:00:00")));
        verify(auditLogRepository).findTop50ByTraceIdOrderByCreatedAtAsc("trace-2");
    }

    @Test
    void deletesAuditEventsCreatedBeforeCutoff() {
        JpaAuditLogStore auditLogStore = new JpaAuditLogStore(auditLogRepository);
        LocalDateTime cutoff = LocalDateTime.parse("2025-12-30T00:00:00");
        when(auditLogRepository.deleteByCreatedAtBefore(cutoff)).thenReturn(4L);

        long deleted = auditLogStore.deleteCreatedBefore(cutoff);

        assertThat(deleted).isEqualTo(4L);
        verify(auditLogRepository).deleteByCreatedAtBefore(cutoff);
    }
}
