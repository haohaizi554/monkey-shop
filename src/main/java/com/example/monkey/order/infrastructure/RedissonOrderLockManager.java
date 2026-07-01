package com.example.monkey.order.infrastructure;

import com.example.monkey.order.domain.OrderLockManager;
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
    private static final Duration LEASE_TIME = Duration.ofSeconds(10);

    private final RedissonClient redissonClient;

    public RedissonOrderLockManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public <T> T withCreateOrderLock(Long userId, Long productId, Supplier<T> operation) {
        RLock lock = redissonClient.getLock(lockName(userId, productId));
        boolean acquired = false;
        try {
            acquired = lock.tryLock(
                    WAIT_TIME.toMillis(), LEASE_TIME.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new BusinessException(ErrorCode.CONFLICT, "Order creation is already in progress");
            }
            return operation.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Order lock acquisition was interrupted");
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Order lock service is unavailable");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private static String lockName(Long userId, Long productId) {
        return "order:user:" + userId + ":monkey:" + productId;
    }
}
