package com.example.monkey.cart.infrastructure;

import com.example.monkey.cart.domain.CartLockManager;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.time.Duration;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RedissonCartLockManager implements CartLockManager {

    private static final Duration WAIT_TIME = Duration.ofSeconds(2);
    private static final String LOCK_PREFIX = "cart:checkout:";
    private static final String REQUIRED_LOCK_MESSAGE = "Checkout Redis lock is required";

    private final RedissonClient redissonClient;

    @Autowired
    public RedissonCartLockManager(
            ObjectProvider<RedissonClient> redissonClientProvider,
            @Value("${app.cart.require-redis-state:false}") boolean requireRedisState) {
        this.redissonClient = redissonClientProvider.getIfAvailable();
        if (requireRedisState && redissonClient == null) {
            throw new IllegalStateException(REQUIRED_LOCK_MESSAGE);
        }
    }

    public RedissonCartLockManager(ObjectProvider<RedissonClient> redissonClientProvider) {
        this(redissonClientProvider, false);
    }

    @Override
    public <T> T withCheckoutLock(Long userId, String idempotencyKey, Supplier<T> action) {
        if (redissonClient == null) {
            return action.get();
        }
        long tenantId = TenantContext.currentTenantIdOrDefault();
        RLock lock = redissonClient.getLock(LOCK_PREFIX + tenantId + ":" + userId + ":" + idempotencyKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(WAIT_TIME.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new BusinessException(ErrorCode.CONFLICT, "Checkout is already in progress");
            }
            return action.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Checkout lock was interrupted");
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Checkout lock is temporarily unavailable");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
