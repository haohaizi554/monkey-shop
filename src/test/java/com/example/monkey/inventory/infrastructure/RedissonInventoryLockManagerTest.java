package com.example.monkey.inventory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.tenant.TenantContext;
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
class RedissonInventoryLockManagerTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @BeforeEach
    void setTenant() {
        TenantContext.setTenantId(9L);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void namespacesStockLockByTenant() throws InterruptedException {
        when(lock.tryLock(2000, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        RedissonInventoryLockManager lockManager = new RedissonInventoryLockManager(redissonClient);
        String result = lockManager.withStockLock(101L, 2200000000001L, () -> "reserved-tenant-9");

        assertThat(result).isEqualTo("reserved-tenant-9");
        verify(redissonClient).getLock("inventory:tenant:9:sku:101:warehouse:2200000000001");

        TenantContext.setTenantId(10L);
        assertThat(lockManager.withStockLock(101L, 2200000000001L, () -> "reserved-tenant-10"))
                .isEqualTo("reserved-tenant-10");

        verify(redissonClient).getLock("inventory:tenant:10:sku:101:warehouse:2200000000001");
        verify(lock, times(2)).unlock();
    }
}
