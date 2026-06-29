package com.example.monkey.infrastructure.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.exception.BusinessException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

@ExtendWith(MockitoExtension.class)
class RedissonOrderLockManagerTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @Test
    void createOrderLockUsesUserAndProductKeyAndUnlocksAfterOperation() throws Exception {
        when(redissonClient.getLock("order:user:42:monkey:7")).thenReturn(lock);
        when(lock.tryLock(2000L, 10000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        RedissonOrderLockManager lockManager =
                new RedissonOrderLockManager(redissonClient, Duration.ofSeconds(2), Duration.ofSeconds(10));

        String result = lockManager.withCreateOrderLock(42L, 7L, () -> "created");

        assertThat(result).isEqualTo("created");
        verify(redissonClient).getLock("order:user:42:monkey:7");
        verify(lock).unlock();
    }

    @Test
    void createOrderLockRejectsConcurrentRequestWhenLockCannotBeAcquired() throws Exception {
        when(redissonClient.getLock("order:user:42:monkey:7")).thenReturn(lock);
        when(lock.tryLock(2000L, 10000L, TimeUnit.MILLISECONDS)).thenReturn(false);
        RedissonOrderLockManager lockManager =
                new RedissonOrderLockManager(redissonClient, Duration.ofSeconds(2), Duration.ofSeconds(10));

        assertThatThrownBy(() -> lockManager.withCreateOrderLock(42L, 7L, () -> "created"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void createOrderLockFailsClosedWhenRedissonIsUnavailable() throws Exception {
        when(redissonClient.getLock("order:user:42:monkey:7")).thenReturn(lock);
        when(lock.tryLock(2000L, 10000L, TimeUnit.MILLISECONDS))
                .thenThrow(new IllegalStateException("redis unavailable"));
        RedissonOrderLockManager lockManager =
                new RedissonOrderLockManager(redissonClient, Duration.ofSeconds(2), Duration.ofSeconds(10));

        assertThatThrownBy(() -> lockManager.withCreateOrderLock(42L, 7L, () -> "created"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void createOrderLockRestoresInterruptFlagWhenInterrupted() throws Exception {
        when(redissonClient.getLock("order:user:42:monkey:7")).thenReturn(lock);
        when(lock.tryLock(2000L, 10000L, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException("interrupted"));
        RedissonOrderLockManager lockManager =
                new RedissonOrderLockManager(redissonClient, Duration.ofSeconds(2), Duration.ofSeconds(10));

        assertThatThrownBy(() -> lockManager.withCreateOrderLock(42L, 7L, () -> "created"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
    }
}
