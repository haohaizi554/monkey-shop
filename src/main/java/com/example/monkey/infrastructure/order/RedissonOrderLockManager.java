package com.example.monkey.infrastructure.order;

import com.example.monkey.domain.order.OrderLockManager;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.exception.BusinessException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RedissonOrderLockManager implements OrderLockManager {

    private static final String ORDER_CREATE_LOCK_PREFIX = "order:user:";

    private final RedissonClient redissonClient;
    private final Duration waitTime;
    private final Duration leaseTime;

    public RedissonOrderLockManager(
            RedissonClient redissonClient,
            @Value("${app.order.lock.wait-time:PT2S}") Duration waitTime,
            @Value("${app.order.lock.lease-time:PT10S}") Duration leaseTime) {
        this.redissonClient = redissonClient;
        this.waitTime = positiveOrDefault(waitTime, Duration.ofSeconds(2));
        this.leaseTime = positiveOrDefault(leaseTime, Duration.ofSeconds(10));
    }

    @Override
    public <T> T withCreateOrderLock(Long userId, Long productId, Supplier<T> operation) {
        RLock lock = redissonClient.getLock(lockName(userId, productId));
        boolean acquired = tryAcquire(lock);
        if (!acquired) {
            throw new BusinessException(ErrorCode.CONFLICT, "Order creation is already in progress");
        }
        try {
            return operation.get();
        } finally {
            unlockIfHeld(lock);
        }
    }

    private boolean tryAcquire(RLock lock) {
        try {
            return lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Order lock acquisition was interrupted");
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Order lock service is unavailable");
        }
    }

    private static void unlockIfHeld(RLock lock) {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    static String lockName(Long userId, Long productId) {
        return ORDER_CREATE_LOCK_PREFIX + userId + ":monkey:" + productId;
    }

    private static Duration positiveOrDefault(Duration value, Duration defaultValue) {
        return value == null || value.isNegative() || value.isZero() ? defaultValue : value;
    }
}
