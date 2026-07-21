package com.example.monkey.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.payment.domain.PaymentMethod;
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

class RedisPaymentCallbackReplayGuardTest {

    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final PaymentCallbackLogRepository callbackLogRepository = mock(PaymentCallbackLogRepository.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final RedisPaymentCallbackReplayGuard guard = guard();

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(7L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(callbackLogRepository.reserve(7L, 100L, "WECHAT", "PAY100", "cb-1"))
                .thenReturn(1);
        when(idGenerator.nextId()).thenReturn(100L);
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
        assertThat(guard.reserve(PaymentMethod.WECHAT, "PAY100", "cb-1", TTL)).isTrue();

        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
        verify(valueOperations, never()).setIfAbsent(any(), any(), any(Duration.class));

        TransactionSynchronizationManager.getSynchronizations().stream()
                .forEach(TransactionSynchronization::afterCommit);

        verify(valueOperations).set("payment:callback:v2:7:WECHAT:cb-1", "PAY100", TTL);
    }

    @Test
    void rollbackDoesNotPublishRedisMarker() {
        assertThat(guard.reserve(PaymentMethod.WECHAT, "PAY100", "cb-1", TTL)).isTrue();

        TransactionSynchronizationManager.clearSynchronization();

        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
        verify(valueOperations, never()).setIfAbsent(any(), any(), any(Duration.class));
    }

    @Test
    void callbackIdCollisionWithAnotherPaymentFailsClosed() {
        PaymentCallbackLogEntity existing = new PaymentCallbackLogEntity();
        existing.setPaymentNo("PAY-OTHER");
        when(callbackLogRepository.reserve(7L, 100L, "WECHAT", "PAY100", "cb-1"))
                .thenReturn(0);
        when(callbackLogRepository.findByTenantIdAndProviderAndCallbackId(7L, PaymentMethod.WECHAT, "cb-1"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> guard.reserve(PaymentMethod.WECHAT, "PAY100", "cb-1", TTL))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @SuppressWarnings("unchecked")
    private RedisPaymentCallbackReplayGuard guard() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        return new RedisPaymentCallbackReplayGuard(provider, callbackLogRepository, idGenerator);
    }
}
