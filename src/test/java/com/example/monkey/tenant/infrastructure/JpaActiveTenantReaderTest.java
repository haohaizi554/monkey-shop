package com.example.monkey.tenant.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.tenant.domain.TenantStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class JpaActiveTenantReaderTest {

    @Test
    void readsOnlyUnexpiredServiceableTenantIdsInStableOrder() {
        TenantRepository tenantRepository = mock(TenantRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T02:00:00Z"), ZoneOffset.UTC);
        LocalDateTime now = LocalDateTime.parse("2026-07-19T02:00:00");
        List<TenantStatus> serviceableStatuses =
                List.of(TenantStatus.TRIAL, TenantStatus.ACTIVE, TenantStatus.DOWNGRADED);
        when(tenantRepository.findServiceableTenantIds(serviceableStatuses, now))
                .thenReturn(List.of(1L, 2L));
        JpaActiveTenantReader reader = new JpaActiveTenantReader(tenantRepository, clock);

        assertThat(reader.findActiveTenantIds()).containsExactly(1L, 2L);
        verify(tenantRepository).findServiceableTenantIds(serviceableStatuses, now);
    }
}
