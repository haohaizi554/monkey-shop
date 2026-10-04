package com.example.monkey.marketing.infrastructure;

import com.example.monkey.marketing.domain.MarketingLockManager;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.infrastructure.lock.TransactionBoundLock;
import java.time.Duration;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class RedissonMarketingLockManager implements MarketingLockManager {

    private static final Duration WAIT_TIME = Duration.ofSeconds(2);

    private final RedissonClient redissonClient;

    public RedissonMarketingLockManager(ObjectProvider<RedissonClient> redissonClientProvider) {
        this.redissonClient = redissonClientProvider.getIfAvailable();
    }

    @Override
    public <T> T withCouponLock(Long couponId, Supplier<T> supplier) {
        return withLock("marketing:tenant:" + TenantContext.currentTenantIdOrDefault() + ":coupon:" + couponId, supplier);
    }

    @Override
    public <T> T withSeckillLock(Long activityId, Supplier<T> supplier) {
        return withLock(
                "marketing:tenant:" + TenantContext.currentTenantIdOrDefault() + ":seckill:activity:" + activityId,
                supplier);
    }

    @Override
    public <T> T withGroupBuyLock(Long teamId, Supplier<T> supplier) {
        return withLock(
                "marketing:tenant:" + TenantContext.currentTenantIdOrDefault() + ":group-buy:team:" + teamId,
                supplier);
    }

    private <T> T withLock(String key, Supplier<T> supplier) {
        if (redissonClient == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Marketing lock service is unavailable");
        }
        try {
            RLock lock = redissonClient.getLock(key);
            return TransactionBoundLock.call(
                    lock,
                    WAIT_TIME,
                    supplier,
                    "Marketing resource is busy",
                    "Marketing lock acquisition was interrupted",
                    "Marketing lock service is unavailable");
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Marketing lock service is unavailable");
        }
    }
}
