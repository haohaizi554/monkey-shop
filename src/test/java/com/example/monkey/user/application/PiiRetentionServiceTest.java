package com.example.monkey.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.order.domain.OrderStatus;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.user.domain.PiiRetentionStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PiiRetentionServiceTest {

    private final PiiRetentionStore piiRetentionStore = Mockito.mock(PiiRetentionStore.class);
    private final PiiRetentionService service = new PiiRetentionService(
            piiRetentionStore,
            Duration.ofDays(183),
            250,
            Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void forgetUserAnonymizesProfileAddressesAndOrders() {
        when(piiRetentionStore.anonymizeUserProfile(42L)).thenReturn(true);

        service.forgetUser(42L);

        verify(piiRetentionStore).anonymizeUserProfile(42L);
        verify(piiRetentionStore).anonymizeAddressesForUser(42L, 250);
        verify(piiRetentionStore).anonymizeOrdersForUser(42L, "anonymous", 250);
    }

    @Test
    void forgetUserRejectsMissingUserBeforeScrubbingRelatedRecords() {
        when(piiRetentionStore.anonymizeUserProfile(42L)).thenReturn(false);

        assertThatThrownBy(() -> service.forgetUser(42L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(piiRetentionStore).anonymizeUserProfile(42L);
        verify(piiRetentionStore, never()).anonymizeAddressesForUser(42L, 250);
        verify(piiRetentionStore, never()).anonymizeOrdersForUser(42L, "anonymous", 250);
    }

    @Test
    void scheduledRetentionAnonymizesFinalOrdersPastRetentionWindow() {
        List<String> finalStatuses = List.of(OrderStatus.COMPLETED.label(), OrderStatus.REFUNDED.label());
        LocalDateTime cutoff = LocalDateTime.parse("2025-12-28T00:00:00");
        when(piiRetentionStore.anonymizeOrdersCreatedBefore(finalStatuses, cutoff, "anonymous", 250))
                .thenReturn(3);

        int count = service.anonymizeCompletedOrdersForRetention();

        assertThat(count).isEqualTo(3);
        verify(piiRetentionStore).anonymizeOrdersCreatedBefore(finalStatuses, cutoff, "anonymous", 250);
    }

    @Test
    void retentionBatchSizeIsClampedToOne() {
        PiiRetentionService oneAtATimeService = new PiiRetentionService(
                piiRetentionStore,
                Duration.ofDays(183),
                0,
                Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC));
        List<String> finalStatuses = List.of(OrderStatus.COMPLETED.label(), OrderStatus.REFUNDED.label());
        LocalDateTime cutoff = LocalDateTime.parse("2025-12-28T00:00:00");

        oneAtATimeService.anonymizeCompletedOrdersForRetention();

        verify(piiRetentionStore).anonymizeOrdersCreatedBefore(finalStatuses, cutoff, "anonymous", 1);
    }

    @Test
    void forgetUserUsesClampedBatchSize() {
        PiiRetentionService oneAtATimeService = new PiiRetentionService(
                piiRetentionStore,
                Duration.ofDays(183),
                0,
                Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC));
        when(piiRetentionStore.anonymizeUserProfile(42L)).thenReturn(true);

        oneAtATimeService.forgetUser(42L);

        verify(piiRetentionStore).anonymizeAddressesForUser(42L, 1);
        verify(piiRetentionStore).anonymizeOrdersForUser(42L, "anonymous", 1);
    }
}
