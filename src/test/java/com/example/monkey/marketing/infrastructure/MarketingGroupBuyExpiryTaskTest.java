package com.example.monkey.marketing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.marketing.application.MarketingApplicationService;
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

class MarketingGroupBuyExpiryTaskTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void expiresGroupBuyTeamsForEveryRetainedTenantAndRestoresAmbientTenant() {
        long activeTenantId = 101L;
        long suspendedTenantId = 202L;
        long expiredTenantId = 303L;
        MarketingApplicationService marketingApplicationService = mock(MarketingApplicationService.class);
        ActiveTenantReader activeTenantReader = mock(ActiveTenantReader.class);
        when(activeTenantReader.findActiveTenantIds()).thenReturn(List.of(activeTenantId));
        when(activeTenantReader.findRetainedTenantIds())
                .thenReturn(List.of(activeTenantId, suspendedTenantId, expiredTenantId));
        ActiveTenantIterator activeTenantIterator = new ActiveTenantIterator(activeTenantReader, transactionManager());
        List<Long> observedTenantIds = new ArrayList<>();
        when(marketingApplicationService.expireGroupBuyTeams()).thenAnswer(invocation -> {
            observedTenantIds.add(TenantContext.currentTenantIdOrDefault());
            return 1;
        });

        TenantContext.setTenantId(909L);
        new MarketingGroupBuyExpiryTask(marketingApplicationService, activeTenantIterator).expireGroupBuyTeams();

        assertThat(observedTenantIds).containsExactly(activeTenantId, suspendedTenantId, expiredTenantId);
        assertThat(TenantContext.currentTenantId()).contains(909L);
        verify(activeTenantReader).findRetainedTenantIds();
        verify(activeTenantReader, never()).findActiveTenantIds();
        verify(marketingApplicationService, times(3)).expireGroupBuyTeams();
    }

    @Test
    void preservesGroupBuyExpirySchedulerContractOnInfrastructureAdapter() throws Exception {
        var method = MarketingGroupBuyExpiryTask.class.getMethod("expireGroupBuyTeams");

        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${app.marketing.group-buy-expire-delay:PT1M}");
        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("marketing-expire-group-buy-teams");
        assertThat(lock.lockAtMostFor()).isEqualTo("${app.marketing.group-buy-expire-lock-at-most-for:PT10M}");
    }

    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        return transactionManager;
    }
}
