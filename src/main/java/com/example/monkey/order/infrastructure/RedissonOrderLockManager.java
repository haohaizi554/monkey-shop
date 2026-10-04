package com.example.monkey.order.infrastructure;

import com.example.monkey.order.domain.OrderLockManager;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.time.Duration;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

@Component
public class RedissonOrderLockManager implements OrderLockManager {

    private static final Duration WAIT_TIME = Duration.ofSeconds(2);

    private final RedissonClient redissonClient;

    public RedissonOrderLockManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public <T> T withCreateOrderLock(Long userId, Long productId, Supplier<T> operation) {
        long tenantId = TenantContext.currentTenantIdOrDefault();
        RLock lock;
        try {
            lock = redissonClient.getLock(lockName(tenantId, userId, productId));
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Order lock service is unavailable", exception);
        }
        boolean acquired = false;
        try {
            // No fixed lease: Redisson's watchdog renews the lock until this thread unlocks after commit.
            acquired = lock.tryLock(WAIT_TIME.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE, "Order lock acquisition was interrupted", exception);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Order lock service is unavailable", exception);
        }
        if (!acquired) {
            throw new BusinessException(ErrorCode.CONFLICT, "Order creation is already in progress");
        }
        try {
            return operation.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private static String lockName(long tenantId, Long userId, Long productId) {
        return "order:tenant:" + tenantId + ":user:" + userId + ":product:" + productId;
    }
}
