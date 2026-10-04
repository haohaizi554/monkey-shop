package com.example.monkey.marketing.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MarketingStore {

    Optional<CouponDefinition> findCoupon(Long couponId);

    Optional<CouponDefinition> findCouponByCode(String code);

    CouponDefinition saveCoupon(CouponDefinition coupon);

    boolean claimCouponQuota(Long couponId);

    Optional<UserCoupon> findUserCoupon(Long userId, Long couponId);

    Optional<UserCoupon> findUserCouponByCode(Long userId, String couponCode);

    UserCoupon saveUserCoupon(UserCoupon coupon);

    boolean redeemUserCouponForOrder(Long userId, String couponCode, Long orderId, LocalDateTime usedAt);

    boolean returnUserCouponForOrder(Long userId, String couponCode, Long orderId);

    boolean redeemUserCouponForCheckout(Long userId, String couponCode, Long checkoutId, LocalDateTime usedAt);

    int returnUserCouponsForCheckout(Long userId, Long checkoutId);

    Optional<SeckillActivity> findSeckillActivity(Long activityId);

    SeckillActivity saveSeckillActivity(SeckillActivity activity);

    boolean reserveSeckillStock(Long activityId, int quantity);

    Optional<SeckillOrder> findSeckillOrder(Long activityId, Long userId, String idempotencyKey);

    int purchasedQuantity(Long activityId, Long userId);

    SeckillOrder saveSeckillOrder(SeckillOrder order);

    Optional<GroupBuyActivity> findGroupBuyActivity(Long activityId);

    Optional<GroupBuyTeam> findGroupBuyTeam(Long teamId);

    GroupBuyTeam saveGroupBuyTeam(GroupBuyTeam team);

    boolean joinOpenGroupBuyTeam(Long teamId);

    boolean hasGroupBuyMember(Long teamId, Long userId);

    void saveGroupBuyMember(Long id, Long teamId, Long userId, String idempotencyKey, LocalDateTime joinedAt);

    List<GroupBuyTeam> findExpiredOpenTeams(LocalDateTime now, int limit);
}
