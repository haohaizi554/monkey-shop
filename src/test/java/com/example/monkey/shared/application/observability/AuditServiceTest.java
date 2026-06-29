package com.example.monkey.shared.application.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.monkey.shared.application.observability.dto.AuditTraceEventDto;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.observability.AuditLogStore;
import com.example.monkey.shared.domain.observability.AuditLogStore.AuditEventRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class AuditServiceTest {

    @Test
    void recordsAuditEventWithHashedSubjectAndNoRawIdentifier() {
        TestAuditLogStore auditLogStore = new TestAuditLogStore();
        AuditService auditService =
                new AuditService(auditLogStore, Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC));
        MDC.put(TraceIds.MDC_KEY, "trace-audit-1");

        try {
            auditService.record(
                    AuditService.LOGIN_FAILURE,
                    AuditService.OUTCOME_FAILURE,
                    null,
                    null,
                    "Alice",
                    "127.0.0.1",
                    "password=plain phone=13800138000");

            AuditEventRecord saved = auditLogStore.savedRecord;
            assertThat(saved.eventType()).isEqualTo(AuditService.LOGIN_FAILURE);
            assertThat(saved.outcome()).isEqualTo(AuditService.OUTCOME_FAILURE);
            assertThat(saved.subjectHash()).hasSize(64);
            assertThat(saved.subjectHash()).doesNotContainIgnoringCase("alice");
            assertThat(saved.sourceIp()).isEqualTo("127.0.0.1");
            assertThat(saved.traceId()).isEqualTo("trace-audit-1");
            assertThat(saved.detail()).isEqualTo("password=**** phone=****");
            assertThat(saved.createdAt()).isEqualTo(LocalDateTime.parse("2026-06-28T00:00:00"));
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
        }
    }

    @Test
    void findsSanitizedAuditEventsByTraceId() {
        TestAuditLogStore auditLogStore = new TestAuditLogStore();
        AuditService auditService = new AuditService(auditLogStore, Clock.systemUTC());
        auditLogStore.traceEvents = List.of(new AuditEventRecord(
                5L,
                AuditService.LOGIN_SUCCESS,
                AuditService.OUTCOME_SUCCESS,
                7L,
                "USER",
                "abc123",
                "203.0.113.10",
                "trace-audit-2",
                "result=success",
                LocalDateTime.parse("2026-06-29T10:15:30")));

        List<AuditTraceEventDto> events = auditService.findByTraceId(" trace-audit-2 ");

        assertThat(events)
                .containsExactly(new AuditTraceEventDto(
                        5L,
                        AuditService.LOGIN_SUCCESS,
                        AuditService.OUTCOME_SUCCESS,
                        7L,
                        "USER",
                        "abc123",
                        "203.0.113.10",
                        "trace-audit-2",
                        "result=success",
                        LocalDateTime.parse("2026-06-29T10:15:30")));
        assertThat(auditLogStore.lastTraceId).isEqualTo("trace-audit-2");
    }

    @Test
    void rejectsBlankTraceIdLookup() {
        AuditService auditService = new AuditService(new TestAuditLogStore(), Clock.systemUTC());

        assertThat(catchThrowable(() -> auditService.findByTraceId(" ")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("traceId is required");
    }

    @Test
    void purgeExpiredAuditLogsDeletesEntriesOlderThanRetention() {
        TestAuditLogStore auditLogStore = new TestAuditLogStore();
        auditLogStore.deleteResult = 3L;
        AuditService auditService = new AuditService(
                auditLogStore, Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC), 180);

        auditService.purgeExpiredAuditLogs();

        assertThat(auditLogStore.deletedBefore).isEqualTo(LocalDateTime.parse("2025-12-30T00:00:00"));
    }

    private static final class TestAuditLogStore implements AuditLogStore {

        private AuditEventRecord savedRecord;
        private List<AuditEventRecord> traceEvents = List.of();
        private String lastTraceId;
        private LocalDateTime deletedBefore;
        private long deleteResult;

        @Override
        public void save(AuditEventRecord record) {
            this.savedRecord = record;
        }

        @Override
        public List<AuditEventRecord> findFirst50ByTraceId(String traceId) {
            this.lastTraceId = traceId;
            return traceEvents;
        }

        @Override
        public long deleteCreatedBefore(LocalDateTime cutoff) {
            this.deletedBefore = cutoff;
            return deleteResult;
        }
    }
}
