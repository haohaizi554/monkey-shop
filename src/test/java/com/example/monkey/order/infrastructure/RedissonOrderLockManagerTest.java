package com.example.monkey.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

@ExtendWith(MockitoExtension.class)
class RedissonOrderLockManagerTest {

    private static final String LOCK_NAME = "order:user:42:monkey:7";

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    private RedissonOrderLockManager orderLockManager;

    @BeforeEach
    void setUp() {
        orderLockManager = new RedissonOrderLockManager(redissonClient);
        when(redissonClient.getLock(LOCK_NAME)).thenReturn(lock);
    }

    @Test
    void runsOperationWhenCreateOrderLockIsAcquired() throws InterruptedException {
        when(lock.tryLock(2000, 10000, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        String result = orderLockManager.withCreateOrderLock(42L, 7L, () -> "created");

        assertThat(result).isEqualTo("created");
        verify(lock).unlock();
    }

    @Test
    void rejectsConcurrentOrderCreationWhenLockCannotBeAcquired() throws InterruptedException {
        when(lock.tryLock(2000, 10000, TimeUnit.MILLISECONDS)).thenReturn(false);

        assertThatThrownBy(() -> orderLockManager.withCreateOrderLock(42L, 7L, () -> "created"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(exception).hasMessage("Order creation is already in progress");
                });

        verify(lock, never()).unlock();
    }

    @Test
    void preservesInterruptStatusWhenLockAcquisitionIsInterrupted() throws InterruptedException {
        when(lock.tryLock(2000, 10000, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException("stop"));

        try {
            assertThatThrownBy(() -> orderLockManager.withCreateOrderLock(42L, 7L, () -> "created"))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                        assertThat(exception).hasMessage("Order lock acquisition was interrupted");
                    });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void wrapsRedissonRuntimeFailures() throws InterruptedException {
        when(lock.tryLock(2000, 10000, TimeUnit.MILLISECONDS)).thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> orderLockManager.withCreateOrderLock(42L, 7L, () -> "created"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(exception).hasMessage("Order lock service is unavailable");
                });
    }

    @Test
    void propagatesBusinessFailuresAndStillUnlocks() throws InterruptedException {
        when(lock.tryLock(2000, 10000, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThatThrownBy(() -> orderLockManager.withCreateOrderLock(42L, 7L, () -> {
                    throw new BusinessException(ErrorCode.OUT_OF_STOCK, "Insufficient stock");
                }))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.OUT_OF_STOCK));

        verify(lock).unlock();
    }
}
