package com.example.monkey.inventory.infrastructure;

import com.example.monkey.inventory.domain.InventoryLockManager;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.infrastructure.lock.TransactionBoundLock;
import java.time.Duration;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

@Component
public class RedissonInventoryLockManager implements InventoryLockManager {

    private static final Duration WAIT_TIME = Duration.ofSeconds(2);

    private final RedissonClient redissonClient;

    public RedissonInventoryLockManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public <T> T withStockLock(Long skuId, Long warehouseId, Supplier<T> action) {
        try {
            RLock lock = redissonClient.getLock(
                    "inventory:tenant:"
                            + TenantContext.currentTenantIdOrDefault()
                            + ":sku:"
                            + skuId
                            + ":warehouse:"
                            + warehouseId);
            return TransactionBoundLock.call(
                    lock,
                    WAIT_TIME,
                    action,
                    "Inventory operation is already in progress",
                    "Inventory lock acquisition was interrupted",
                    "Inventory lock service is unavailable");
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Inventory lock service is unavailable");
        }
    }
}
