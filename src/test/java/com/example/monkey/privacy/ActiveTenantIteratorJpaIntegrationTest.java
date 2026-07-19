package com.example.monkey.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.tenant.ActiveTenantIterator;
import com.example.monkey.shared.application.tenant.ActiveTenantIterator.IterationResult;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.infrastructure.observability.AuditLog;
import com.example.monkey.shared.infrastructure.observability.AuditLogRepository;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.example.monkey.tenant.domain.ActiveTenantReader;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@Import(ActiveTenantIterator.class)
@MockitoBean(types = PiiCryptoService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActiveTenantIteratorJpaIntegrationTest {

    private static final String TRACE_ID = "stage-9b-tenant-transaction";

    private final ActiveTenantIterator iterator;
    private final AuditLogRepository auditLogRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;

    @MockitoBean
    private ActiveTenantReader activeTenantReader;

    @Autowired
    ActiveTenantIteratorJpaIntegrationTest(
            ActiveTenantIterator iterator,
            AuditLogRepository auditLogRepository,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        this.iterator = iterator;
        this.auditLogRepository = auditLogRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionManager = transactionManager;
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        jdbcTemplate.update("DELETE FROM audit_log WHERE trace_id = ?", TRACE_ID);
    }

    @Test
    void commitsEachTenantIndependentlyAndAppliesTenantFilterAfterOuterRollback() {
        when(activeTenantReader.findActiveTenantIds()).thenReturn(List.of(1L, 2L));
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);

        IterationResult result = outerTransaction.execute(status -> {
            IterationResult iterationResult = iterator.forEachActiveTenant(tenantId -> {
                auditLogRepository.saveAndFlush(auditLog());
                if (tenantId == 1L) {
                    throw new IllegalStateException("rollback tenant one");
                }
                return 1L;
            });
            status.setRollbackOnly();
            return iterationResult;
        });

        assertThat(result).isNotNull();
        assertThat(result.successfulTenantIds()).containsExactly(2L);
        assertThat(result.failedTenantIds()).containsExactly(1L);
        assertThat(result.affectedRows()).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM audit_log WHERE trace_id = ?", Long.class, TRACE_ID))
                .isEqualTo(1L);
        assertThat(findVisibleTraceIds(1L)).isEmpty();
        assertThat(findVisibleTraceIds(2L)).containsExactly(TRACE_ID);
        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    private List<String> findVisibleTraceIds(Long tenantId) {
        TenantContext.setTenantId(tenantId);
        try {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            return transaction.execute(
                    status -> auditLogRepository.findTop50ByTraceIdOrderByCreatedAtAsc(TRACE_ID).stream()
                            .map(AuditLog::getTraceId)
                            .toList());
        } finally {
            TenantContext.clear();
        }
    }

    private static AuditLog auditLog() {
        AuditLog auditLog = new AuditLog();
        auditLog.setEventType("PRIVACY_RETENTION");
        auditLog.setOutcome("SUCCESS");
        auditLog.setTraceId(TRACE_ID);
        auditLog.setCreatedAt(LocalDateTime.parse("2026-07-19T04:00:00"));
        return auditLog;
    }
}
