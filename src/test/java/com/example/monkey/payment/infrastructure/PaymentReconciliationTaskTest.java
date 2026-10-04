package com.example.monkey.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.payment.application.PaymentApplicationService;
import com.example.monkey.shared.application.tenant.ActiveTenantIterator;
import com.example.monkey.shared.application.tenant.ActiveTenantIterator.IterationResult;
import com.example.monkey.shared.application.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
class PaymentReconciliationTaskTest {

    @Mock
    private PaymentApplicationService paymentApplicationService;

    @Mock
    private ActiveTenantIterator activeTenantIterator;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void reconciliationRunsForEachRetainedTenantWithoutUsingAmbientDefault() throws Exception {
        List<Long> observedTenantIds = new ArrayList<>();
        when(paymentApplicationService.reconcileYesterday()).thenAnswer(invocation -> {
            observedTenantIds.add(TenantContext.currentTenantIdOrDefault());
            return null;
        });
        when(activeTenantIterator.forEachRetainedTenant(any())).thenAnswer(invocation -> {
            ActiveTenantIterator.TenantWork work = invocation.getArgument(0);
            executeForTenant(work, 1L);
            executeForTenant(work, 2L);
            return new IterationResult(List.of(1L, 2L), List.of(), 2L);
        });

        PaymentReconciliationTask task = new PaymentReconciliationTask(paymentApplicationService, activeTenantIterator);

        TenantContext.clear();
        task.reconcileYesterday();

        assertThat(observedTenantIds).containsExactly(1L, 2L);
        assertThat(TenantContext.currentTenantId()).isEmpty();
        assertThat(PaymentReconciliationTask.class.getMethod("reconcileYesterday").isAnnotationPresent(Scheduled.class))
                .isTrue();
        verify(activeTenantIterator).forEachRetainedTenant(any());
        verify(activeTenantIterator, never()).forEachActiveTenant(any());
    }

    private static void executeForTenant(ActiveTenantIterator.TenantWork work, Long tenantId) {
        TenantContext.setTenantId(tenantId);
        try {
            work.execute(tenantId);
        } finally {
            TenantContext.clear();
        }
    }
}
