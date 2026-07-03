package com.example.monkey.marketing.infrastructure;

import com.example.monkey.marketing.domain.MarketingLockManager;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class RedissonMarketingLockManager implements MarketingLockManager {

    private static final Duration WAIT_TIME = Duration.ofSeconds(2);
    private static final Duration LEASE_TIME = Duration.ofSeconds(10);

    private final RedissonClient redissonClient;

    public RedissonMarketingLockManager(ObjectProvider<RedissonClient> redissonClientProvider) {
        this.redissonClient = redissonClientProvider.getIfAvailable();
    }

    @Override
    public <T> T withCouponLock(Long couponId, Supplier<T> supplier) {
        return withLock("marketing:coupon:" + couponId, supplier);
    }

    @Override
    public <T> T withSeckillLock(Long activityId, Supplier<T> supplier) {
        return withLock("marketing:seckill:activity:" + activityId, supplier);
    }

    @Override
    public <T> T withGroupBuyLock(Long teamId, Supplier<T> supplier) {
        return withLock("marketing:group-buy:team:" + teamId, supplier);
    }

    private <T> T withLock(String key, Supplier<T> supplier) {
        if (redissonClient == null) {
            return supplier.get();
        }
        RLock lock = redissonClient.getLock(key);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(WAIT_TIME.toMillis(), LEASE_TIME.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new BusinessException(ErrorCode.CONFLICT, "Marketing resource is busy");
            }
            return supplier.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Marketing lock acquisition was interrupted");
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Marketing lock service is unavailable");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
