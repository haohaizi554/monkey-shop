package com.example.monkey.marketing.domain;

import java.util.function.Supplier;

public interface MarketingLockManager {

    <T> T withCouponLock(Long couponId, Supplier<T> supplier);

    <T> T withSeckillLock(Long activityId, Supplier<T> supplier);

    <T> T withGroupBuyLock(Long teamId, Supplier<T> supplier);
}
