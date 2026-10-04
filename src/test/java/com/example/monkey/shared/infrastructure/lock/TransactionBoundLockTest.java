package com.example.monkey.shared.infrastructure.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class TransactionBoundLockTest {

    @Mock
    private RLock lock;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void propagatesInventoryFailuresWithTheirCause() throws InterruptedException {
        when(lock.tryLock(2000, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThatThrownBy(() -> TransactionBoundLock.call(
                        lock,
                        Duration.ofSeconds(2),
                        () -> {
                            throw new IllegalStateException("Insufficient locked inventory");
                        },
                        "busy",
                        "interrupted",
                        "unavailable"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Insufficient locked inventory");

        verify(lock).unlock();
    }

    @Test
    void keepsTheRedissonCauseWhenTheLockCannotBeTaken() throws InterruptedException {
        IllegalStateException redisDown = new IllegalStateException("redis down");
        when(lock.tryLock(2000, TimeUnit.MILLISECONDS)).thenThrow(redisDown);

        assertThatThrownBy(() -> TransactionBoundLock.call(
                        lock, Duration.ofSeconds(2), () -> "reserved", "busy", "interrupted", "unavailable"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(exception).hasMessage("unavailable");
                    assertThat(exception).hasCause(redisDown);
                });

        verify(lock, never()).unlock();
    }

    @Test
    void holdsTheLockUntilTheTransactionCompletes() throws InterruptedException {
        when(lock.tryLock(2000, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        AtomicBoolean unlockedDuringAction = new AtomicBoolean();

        String result = TransactionBoundLock.call(
                lock,
                Duration.ofSeconds(2),
                () -> {
                    unlockedDuringAction.set(!lock.isHeldByCurrentThread());
                    return "reserved";
                },
                "busy",
                "interrupted",
                "unavailable");

        assertThat(result).isEqualTo("reserved");
        assertThat(unlockedDuringAction).isFalse();
        verify(lock, never()).unlock();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(0));
        verify(lock).unlock();
    }
}
