package com.example.monkey.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.order.application.OrderService;
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

@ExtendWith(MockitoExtension.class)
class OrderAutoReceiveTaskTest {

    @Mock
    private OrderService orderService;

    @Mock
    private ActiveTenantIterator activeTenantIterator;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void overdueReceiptRunsForEachRetainedTenantIncludingNonServiceableTenants() {
        List<Long> observedTenantIds = new ArrayList<>();
        when(orderService.autoReceiveOverdueShipments()).thenAnswer(invocation -> {
            observedTenantIds.add(TenantContext.currentTenantIdOrDefault());
            return 1;
        });
        when(activeTenantIterator.forEachRetainedTenant(any())).thenAnswer(invocation -> {
            ActiveTenantIterator.TenantWork work = invocation.getArgument(0);
            executeForTenant(work, 2L);
            executeForTenant(work, 3L);
            return new IterationResult(List.of(2L, 3L), List.of(), 2L);
        });

        TenantContext.clear();
        new OrderAutoReceiveTask(orderService, activeTenantIterator).autoReceiveOverdueShipments();

        assertThat(observedTenantIds).containsExactly(2L, 3L);
        assertThat(TenantContext.currentTenantId()).isEmpty();
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
