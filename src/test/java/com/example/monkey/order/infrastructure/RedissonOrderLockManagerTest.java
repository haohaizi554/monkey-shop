package com.example.monkey.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

@ExtendWith(MockitoExtension.class)
class RedissonOrderLockManagerTest {

    private static final String LOCK_NAME = "order:tenant:9:user:42:product:7";

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    private RedissonOrderLockManager orderLockManager;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(9L);
        orderLockManager = new RedissonOrderLockManager(redissonClient);
        when(redissonClient.getLock(LOCK_NAME)).thenReturn(lock);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void runsOperationWhenCreateOrderLockIsAcquired() throws InterruptedException {
        when(lock.tryLock(2000, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        String result = orderLockManager.withCreateOrderLock(42L, 7L, () -> "created");

        assertThat(result).isEqualTo("created");
        verify(lock).unlock();
    }

    @Test
    void isolatesCreateOrderLocksAcrossTenantsWithTheSameUserAndProductIds() throws InterruptedException {
        when(lock.tryLock(2000, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        TenantContext.setTenantId(9L);
        assertThat(orderLockManager.withCreateOrderLock(42L, 7L, () -> "tenant-9"))
                .isEqualTo("tenant-9");
        TenantContext.setTenantId(10L);
        when(redissonClient.getLock("order:tenant:10:user:42:product:7")).thenReturn(lock);
        assertThat(orderLockManager.withCreateOrderLock(42L, 7L, () -> "tenant-10"))
                .isEqualTo("tenant-10");

        verify(redissonClient).getLock("order:tenant:9:user:42:product:7");
        verify(redissonClient).getLock("order:tenant:10:user:42:product:7");
    }

    @Test
    void rejectsConcurrentOrderCreationWhenLockCannotBeAcquired() throws InterruptedException {
        when(lock.tryLock(2000, TimeUnit.MILLISECONDS)).thenReturn(false);

        assertThatThrownBy(() -> orderLockManager.withCreateOrderLock(42L, 7L, () -> "created"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(exception).hasMessage("Order creation is already in progress");
                });

        verify(lock, never()).unlock();
    }

    @Test
    void preservesInterruptStatusWhenLockAcquisitionIsInterrupted() throws InterruptedException {
        when(lock.tryLock(2000, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException("stop"));

        try {
            assertThatThrownBy(() -> orderLockManager.withCreateOrderLock(42L, 7L, () -> "created"))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                        assertThat(exception).hasMessage("Order lock acquisition was interrupted");
                    });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            assertThat(Thread.interrupted()).isTrue();
        }
    }

    @Test
    void wrapsRedissonRuntimeFailures() throws InterruptedException {
        when(lock.tryLock(2000, TimeUnit.MILLISECONDS)).thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> orderLockManager.withCreateOrderLock(42L, 7L, () -> "created"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(exception).hasMessage("Order lock service is unavailable");
                    assertThat(exception).hasCauseInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void propagatesOrderCreationFailuresInsteadOfReportingTheLockAsUnavailable() throws InterruptedException {
        when(lock.tryLock(2000, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThatThrownBy(() -> orderLockManager.withCreateOrderLock(42L, 7L, () -> {
                    throw new IllegalStateException("Insufficient locked inventory");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Insufficient locked inventory");

        verify(lock).unlock();
    }

    @Test
    void propagatesBusinessFailuresAndStillUnlocks() throws InterruptedException {
        when(lock.tryLock(2000, TimeUnit.MILLISECONDS)).thenReturn(true);
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
