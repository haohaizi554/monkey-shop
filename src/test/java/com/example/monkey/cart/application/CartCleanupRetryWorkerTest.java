package com.example.monkey.cart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.cart.domain.CartCleanupIntentStore;
import com.example.monkey.cart.domain.CartCleanupTenantSource;
import com.example.monkey.shared.application.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class CartCleanupRetryWorkerTest {

    @Test
    void continuesWithLaterDueIntentsWhenOneAttemptFails() {
        CartCleanupIntentStore intentStore = mock(CartCleanupIntentStore.class);
        CartCleanupTenantSource tenantSource = mock(CartCleanupTenantSource.class);
        CartCleanupProcessor processor = mock(CartCleanupProcessor.class);
        when(tenantSource.findTenantIdsWithReadyIntents(any(), anyLong(), anyInt()))
                .thenAnswer(invocation -> invocation.<Long>getArgument(1) == 0L ? List.of(11L, 22L) : List.of());
        when(intentStore.findReadyCheckoutIds(any())).thenAnswer(invocation -> {
            long tenantId = TenantContext.currentTenantIdOrDefault();
            if (tenantId == 11L) {
                return List.of(101L);
            }
            if (tenantId == 22L) {
                return List.of(102L);
            }
            throw new AssertionError("Intent lookup ran outside its tenant context");
        });
        doThrow(new IllegalStateException("cleanup state unavailable"))
                .when(processor)
                .process(101L);

        TenantContext.setTenantId(77L);
        try {
            new CartCleanupRetryWorker(intentStore, tenantSource, processor).retryPending();
        } finally {
            assertThat(TenantContext.currentTenantIdOrDefault()).isEqualTo(77L);
            TenantContext.clear();
        }

        verify(processor, times(1)).process(101L);
        verify(processor, times(1)).process(102L);
    }
}
