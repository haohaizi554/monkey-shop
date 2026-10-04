package com.example.monkey.membership.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.membership.application.MembershipApplicationService;
import com.example.monkey.membership.application.dto.PriceDropScanResponseDto;
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
class MembershipPriceDropTaskTest {

    @Mock
    private MembershipApplicationService membershipApplicationService;

    @Mock
    private ActiveTenantIterator activeTenantIterator;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void priceDropScanRunsForEachServiceableTenantWithoutAmbientDefault() {
        List<Long> observedTenantIds = new ArrayList<>();
        when(membershipApplicationService.scanPriceDrops()).thenAnswer(invocation -> {
            observedTenantIds.add(TenantContext.currentTenantIdOrDefault());
            return new PriceDropScanResponseDto(1, 1);
        });
        when(activeTenantIterator.forEachActiveTenant(any())).thenAnswer(invocation -> {
            ActiveTenantIterator.TenantWork work = invocation.getArgument(0);
            executeForTenant(work, 1L);
            executeForTenant(work, 2L);
            return new IterationResult(List.of(1L, 2L), List.of(), 2L);
        });

        TenantContext.clear();
        new MembershipPriceDropTask(membershipApplicationService, activeTenantIterator).scanPriceDrops();

        assertThat(observedTenantIds).containsExactly(1L, 2L);
        assertThat(TenantContext.currentTenantId()).isEmpty();
        verify(activeTenantIterator).forEachActiveTenant(any());
        verify(activeTenantIterator, never()).forEachRetainedTenant(any());
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
