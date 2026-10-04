package com.example.monkey.logistics.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.logistics.domain.LogisticsCarrier;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RedisLogisticsWebhookReplayGuardTest {

    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final LogisticsWebhookLogRepository webhookLogRepository = mock(LogisticsWebhookLogRepository.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final RedisLogisticsWebhookReplayGuard guard = guard();

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(7L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(idGenerator.nextId()).thenReturn(9000L);
        when(webhookLogRepository.reserve(7L, 9000L, "SF", "SF7000", "event-1", "127.0.0.1"))
                .thenReturn(1);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TenantContext.clear();
    }

    @Test
    void redisMarkerIsPublishedOnlyAfterDatabaseCommit() {
        assertThat(guard.reserve(LogisticsCarrier.SF, "SF7000", "event-1", TTL, "127.0.0.1"))
                .isTrue();

        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
        verify(valueOperations, never()).setIfAbsent(any(), any(), any(Duration.class));

        TransactionSynchronizationManager.getSynchronizations().stream()
                .forEach(TransactionSynchronization::afterCommit);

        verify(valueOperations).set("logistics:webhook:v2:7:SF:event-1", "SF7000", TTL);
    }

    @Test
    void rollbackDoesNotPublishRedisMarker() {
        assertThat(guard.reserve(LogisticsCarrier.SF, "SF7000", "event-1", TTL, "127.0.0.1"))
                .isTrue();

        TransactionSynchronizationManager.clearSynchronization();

        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
        verify(valueOperations, never()).setIfAbsent(any(), any(), any(Duration.class));
    }

    @Test
    void databaseFailureCannotLeaveAReplayMarkerThatSuppressesRetry() {
        when(webhookLogRepository.reserve(7L, 9000L, "SF", "SF7000", "event-1", "127.0.0.1"))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> guard.reserve(LogisticsCarrier.SF, "SF7000", "event-1", TTL, "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");

        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
        verify(valueOperations, never()).setIfAbsent(any(), any(), any(Duration.class));
    }

    @Test
    void sameCarrierEventIdCanBeReservedIndependentlyByAnotherTenant() {
        when(webhookLogRepository.reserve(8L, 9000L, "SF", "SF8000", "event-1", "127.0.0.2"))
                .thenReturn(1);

        TenantContext.setTenantId(8L);

        assertThat(guard.reserve(LogisticsCarrier.SF, "SF8000", "event-1", TTL, "127.0.0.2"))
                .isTrue();
        verify(webhookLogRepository).reserve(8L, 9000L, "SF", "SF8000", "event-1", "127.0.0.2");

        TransactionSynchronizationManager.getSynchronizations().stream()
                .forEach(TransactionSynchronization::afterCommit);
        verify(valueOperations).set("logistics:webhook:v2:8:SF:event-1", "SF8000", TTL);
    }

    @Test
    void identicalDatabaseReplayReturnsFalseWithoutPublishingRedis() {
        LogisticsWebhookLogEntity existing = new LogisticsWebhookLogEntity();
        existing.setTrackingNo("SF7000");
        when(webhookLogRepository.reserve(7L, 9000L, "SF", "SF7000", "event-1", "127.0.0.1"))
                .thenReturn(0);
        when(webhookLogRepository.findByTenantIdAndCarrierAndEventId(7L, LogisticsCarrier.SF, "event-1"))
                .thenReturn(Optional.of(existing));

        assertThat(guard.reserve(LogisticsCarrier.SF, "SF7000", "event-1", TTL, "127.0.0.1"))
                .isFalse();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void eventIdCollisionWithAnotherTrackingNumberFailsClosed() {
        LogisticsWebhookLogEntity existing = new LogisticsWebhookLogEntity();
        existing.setTrackingNo("SF-OTHER");
        when(webhookLogRepository.reserve(7L, 9000L, "SF", "SF7000", "event-1", "127.0.0.1"))
                .thenReturn(0);
        when(webhookLogRepository.findByTenantIdAndCarrierAndEventId(7L, LogisticsCarrier.SF, "event-1"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> guard.reserve(LogisticsCarrier.SF, "SF7000", "event-1", TTL, "127.0.0.1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
    }

    @SuppressWarnings("unchecked")
    private RedisLogisticsWebhookReplayGuard guard() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        return new RedisLogisticsWebhookReplayGuard(provider, webhookLogRepository, idGenerator);
    }
}
