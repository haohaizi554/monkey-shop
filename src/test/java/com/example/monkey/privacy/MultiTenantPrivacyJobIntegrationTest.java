package com.example.monkey.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.tenant.ActiveTenantIterator;
import com.example.monkey.shared.application.tenant.ActiveTenantIterator.IterationResult;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.observability.AuditLogStore;
import com.example.monkey.tenant.domain.ActiveTenantReader;
import com.example.monkey.user.application.PiiRetentionService;
import com.example.monkey.user.domain.PiiRetentionStore;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MultiTenantPrivacyJobIntegrationTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void iteratorVisitsEveryActiveTenantAndRestoresPreviousContextAfterFailure() {
        ActiveTenantIterator iterator = new ActiveTenantIterator(activeTenants(1L, 2L), transactionManager());
        List<Long> processedTenantIds = new ArrayList<>();
        TenantContext.setTenantId(99L);

        IterationResult result = iterator.forEachActiveTenant(tenantId -> {
            processedTenantIds.add(TenantContext.currentTenantIdOrDefault());
            if (tenantId == 1L) {
                throw new IllegalStateException("tenant one failed");
            }
            return 3L;
        });

        assertThat(processedTenantIds).containsExactly(1L, 2L);
        assertThat(result.successfulTenantIds()).containsExactly(2L);
        assertThat(result.failedTenantIds()).containsExactly(1L);
        assertThat(result.affectedRows()).isEqualTo(3L);
        assertThat(TenantContext.currentTenantId()).contains(99L);
    }

    @Test
    void retentionAndAuditJobsExecuteForTenantOneAndTwoSeparately() {
        ActiveTenantIterator iterator = new ActiveTenantIterator(activeTenants(1L, 2L), transactionManager());
        List<Long> retentionTenantIds = new ArrayList<>();
        PiiRetentionStore retentionStore = mock(PiiRetentionStore.class);
        when(retentionStore.anonymizeOrdersCreatedBefore(any(), any(LocalDateTime.class), any(), any(Integer.class)))
                .thenAnswer(invocation -> {
                    retentionTenantIds.add(TenantContext.currentTenantIdOrDefault());
                    return 1;
                });
        PiiRetentionService retentionService =
                new PiiRetentionService(retentionStore, iterator, Duration.ofDays(183), 250);

        int anonymized = retentionService.anonymizeCompletedOrdersForRetention();

        List<Long> auditTenantIds = new ArrayList<>();
        AuditLogStore auditLogStore = mock(AuditLogStore.class);
        when(auditLogStore.deleteCreatedBefore(any(LocalDateTime.class))).thenAnswer(invocation -> {
            auditTenantIds.add(TenantContext.currentTenantIdOrDefault());
            return 1L;
        });
        AuditService auditService = new AuditService(auditLogStore, iterator, 180);

        auditService.purgeExpiredAuditLogs();

        assertThat(anonymized).isEqualTo(2);
        assertThat(retentionTenantIds).containsExactly(1L, 2L);
        assertThat(auditTenantIds).containsExactly(1L, 2L);
        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    private static ActiveTenantReader activeTenants(Long... tenantIds) {
        return () -> List.of(tenantIds);
    }

    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        return transactionManager;
    }
}
