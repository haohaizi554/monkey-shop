package com.example.monkey.search.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.search.application.SearchApplicationService;
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
class SearchHotKeywordTaskTest {

    @Mock
    private SearchApplicationService searchApplicationService;

    @Mock
    private ActiveTenantIterator activeTenantIterator;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void hotKeywordRefreshRunsForEachServiceableTenantWithoutAmbientDefault() {
        List<Long> observedTenantIds = new ArrayList<>();
        doAnswer(invocation -> {
                    observedTenantIds.add(TenantContext.currentTenantIdOrDefault());
                    return null;
                })
                .when(searchApplicationService)
                .refreshHotKeywordSnapshot();
        when(activeTenantIterator.forEachActiveTenant(any())).thenAnswer(invocation -> {
            ActiveTenantIterator.TenantWork work = invocation.getArgument(0);
            executeForTenant(work, 1L);
            executeForTenant(work, 2L);
            return new IterationResult(List.of(1L, 2L), List.of(), 0L);
        });

        TenantContext.clear();
        new SearchHotKeywordTask(searchApplicationService, activeTenantIterator).refreshHotKeywordSnapshot();

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
