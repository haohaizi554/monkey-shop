package com.example.monkey.inventory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.inventory.application.InventoryApplicationService;
import com.example.monkey.shared.application.tenant.ActiveTenantIterator;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.tenant.domain.ActiveTenantReader;
import java.util.ArrayList;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class InventoryReservationExpiryTaskTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void releasesReservationsForEveryRetainedTenantAndRestoresAmbientTenant() {
        long activeTenantId = 101L;
        long suspendedTenantId = 202L;
        long expiredTenantId = 303L;
        InventoryApplicationService inventoryApplicationService = mock(InventoryApplicationService.class);
        ActiveTenantReader activeTenantReader = mock(ActiveTenantReader.class);
        when(activeTenantReader.findActiveTenantIds()).thenReturn(List.of(activeTenantId));
        when(activeTenantReader.findRetainedTenantIds())
                .thenReturn(List.of(activeTenantId, suspendedTenantId, expiredTenantId));
        ActiveTenantIterator activeTenantIterator = new ActiveTenantIterator(activeTenantReader, transactionManager());
        List<Long> observedTenantIds = new ArrayList<>();
        when(inventoryApplicationService.releaseExpiredReservations()).thenAnswer(invocation -> {
            observedTenantIds.add(TenantContext.currentTenantIdOrDefault());
            return 1;
        });

        TenantContext.setTenantId(909L);
        new InventoryReservationExpiryTask(inventoryApplicationService, activeTenantIterator)
                .releaseExpiredReservations();

        assertThat(observedTenantIds).containsExactly(activeTenantId, suspendedTenantId, expiredTenantId);
        assertThat(TenantContext.currentTenantId()).contains(909L);
        verify(activeTenantReader).findRetainedTenantIds();
        verify(activeTenantReader, never()).findActiveTenantIds();
        verify(inventoryApplicationService, times(3)).releaseExpiredReservations();
    }

    @Test
    void preservesExpirySchedulerContractOnInfrastructureAdapter() throws Exception {
        var method = InventoryReservationExpiryTask.class.getMethod("releaseExpiredReservations");

        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${app.inventory.release-expired-delay:PT1M}");
        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("inventory-release-expired-reservations");
        assertThat(lock.lockAtMostFor()).isEqualTo("${app.inventory.release-lock-at-most-for:PT10M}");
    }

    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        return transactionManager;
    }
}
