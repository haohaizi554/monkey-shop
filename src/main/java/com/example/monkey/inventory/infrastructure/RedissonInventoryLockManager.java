package com.example.monkey.inventory.infrastructure;

import com.example.monkey.inventory.domain.InventoryLockManager;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

@Component
public class RedissonInventoryLockManager implements InventoryLockManager {

    private static final Duration WAIT_TIME = Duration.ofSeconds(2);
    private static final Duration LEASE_TIME = Duration.ofSeconds(10);

    private final RedissonClient redissonClient;

    public RedissonInventoryLockManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public <T> T withStockLock(Long skuId, Long warehouseId, Supplier<T> action) {
        RLock lock = redissonClient.getLock("inventory:sku:" + skuId + ":warehouse:" + warehouseId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(WAIT_TIME.toMillis(), LEASE_TIME.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new BusinessException(ErrorCode.CONFLICT, "Inventory operation is already in progress");
            }
            return action.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Inventory lock acquisition was interrupted");
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Inventory lock service is unavailable");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
