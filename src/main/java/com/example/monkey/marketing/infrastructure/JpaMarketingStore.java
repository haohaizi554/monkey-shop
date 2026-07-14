package com.example.monkey.marketing.infrastructure;

import com.example.monkey.marketing.domain.CouponDefinition;
import com.example.monkey.marketing.domain.GroupBuyActivity;
import com.example.monkey.marketing.domain.GroupBuyStatus;
import com.example.monkey.marketing.domain.GroupBuyTeam;
import com.example.monkey.marketing.domain.MarketingStore;
import com.example.monkey.marketing.domain.SeckillActivity;
import com.example.monkey.marketing.domain.SeckillOrder;
import com.example.monkey.marketing.domain.UserCoupon;
import com.example.monkey.shared.application.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.marketing.store", havingValue = "jpa", matchIfMissing = true)
public class JpaMarketingStore implements MarketingStore {

    private final MarketingCouponRepository couponRepository;
    private final MarketingUserCouponRepository userCouponRepository;
    private final MarketingSeckillActivityRepository seckillActivityRepository;
    private final MarketingSeckillOrderRepository seckillOrderRepository;
    private final MarketingGroupBuyActivityRepository groupBuyActivityRepository;
    private final MarketingGroupBuyTeamRepository groupBuyTeamRepository;
    private final MarketingGroupBuyMemberRepository groupBuyMemberRepository;

    public JpaMarketingStore(
            MarketingCouponRepository couponRepository,
            MarketingUserCouponRepository userCouponRepository,
            MarketingSeckillActivityRepository seckillActivityRepository,
            MarketingSeckillOrderRepository seckillOrderRepository,
            MarketingGroupBuyActivityRepository groupBuyActivityRepository,
            MarketingGroupBuyTeamRepository groupBuyTeamRepository,
            MarketingGroupBuyMemberRepository groupBuyMemberRepository) {
        this.couponRepository = couponRepository;
        this.userCouponRepository = userCouponRepository;
        this.seckillActivityRepository = seckillActivityRepository;
        this.seckillOrderRepository = seckillOrderRepository;
        this.groupBuyActivityRepository = groupBuyActivityRepository;
        this.groupBuyTeamRepository = groupBuyTeamRepository;
        this.groupBuyMemberRepository = groupBuyMemberRepository;
    }

    @Override
    public Optional<CouponDefinition> findCoupon(Long couponId) {
        return couponRepository.findById(couponId).map(JpaMarketingStore::toDomain);
    }

    @Override
    public Optional<CouponDefinition> findCouponByCode(String code) {
        return couponRepository.findByCode(code).map(JpaMarketingStore::toDomain);
    }

    @Override
    public CouponDefinition saveCoupon(CouponDefinition coupon) {
        return toDomain(couponRepository.save(toEntity(coupon)));
    }

    @Override
    public Optional<UserCoupon> findUserCoupon(Long userId, Long couponId) {
        return userCouponRepository.findByUserIdAndCouponId(userId, couponId).map(JpaMarketingStore::toDomain);
    }

    @Override
    public Optional<UserCoupon> findUserCouponByCode(Long userId, String couponCode) {
        return userCouponRepository
                .findByUserIdAndCouponCode(userId, couponCode)
                .map(JpaMarketingStore::toDomain);
    }

    @Override
    public UserCoupon saveUserCoupon(UserCoupon coupon) {
        return toDomain(userCouponRepository.save(toEntity(coupon)));
    }

    @Override
    public boolean redeemUserCouponForOrder(Long userId, String couponCode, Long orderId, LocalDateTime usedAt) {
        return userCouponRepository.redeemClaimedForOrder(
                        TenantContext.currentTenantIdOrDefault(), userId, couponCode, orderId, usedAt)
                == 1;
    }

    @Override
    public boolean returnUserCouponForOrder(Long userId, String couponCode, Long orderId) {
        return userCouponRepository.returnRedeemedForOrder(
                        TenantContext.currentTenantIdOrDefault(), userId, couponCode, orderId)
                == 1;
    }

    @Override
    public boolean redeemUserCouponForCheckout(Long userId, String couponCode, Long checkoutId, LocalDateTime usedAt) {
        return userCouponRepository.redeemClaimedForCheckout(
                        TenantContext.currentTenantIdOrDefault(), userId, couponCode, checkoutId, usedAt)
                == 1;
    }

    @Override
    public int returnUserCouponsForCheckout(Long userId, Long checkoutId) {
        return userCouponRepository.returnRedeemedForCheckout(
                TenantContext.currentTenantIdOrDefault(), userId, checkoutId);
    }

    @Override
    public Optional<SeckillActivity> findSeckillActivity(Long activityId) {
        return seckillActivityRepository.findById(activityId).map(JpaMarketingStore::toDomain);
    }

    @Override
    public SeckillActivity saveSeckillActivity(SeckillActivity activity) {
        return toDomain(seckillActivityRepository.save(toEntity(activity)));
    }

    @Override
    public Optional<SeckillOrder> findSeckillOrder(Long activityId, Long userId, String idempotencyKey) {
        return seckillOrderRepository
                .findByActivityIdAndUserIdAndIdempotencyKey(activityId, userId, "seckill:" + idempotencyKey)
                .map(JpaMarketingStore::toDomain);
    }

    @Override
    public int purchasedQuantity(Long activityId, Long userId) {
        return seckillOrderRepository.purchasedQuantity(activityId, userId);
    }

    @Override
    public SeckillOrder saveSeckillOrder(SeckillOrder order) {
        return toDomain(seckillOrderRepository.save(toEntity(order)));
    }

    @Override
    public Optional<GroupBuyActivity> findGroupBuyActivity(Long activityId) {
        return groupBuyActivityRepository.findById(activityId).map(JpaMarketingStore::toDomain);
    }

    @Override
    public Optional<GroupBuyTeam> findGroupBuyTeam(Long teamId) {
        return groupBuyTeamRepository.findById(teamId).map(JpaMarketingStore::toDomain);
    }

    @Override
    public GroupBuyTeam saveGroupBuyTeam(GroupBuyTeam team) {
        return toDomain(groupBuyTeamRepository.save(toEntity(team)));
    }

    @Override
    public boolean hasGroupBuyMember(Long teamId, Long userId) {
        return groupBuyMemberRepository.existsByTeamIdAndUserId(teamId, userId);
    }

    @Override
    public void saveGroupBuyMember(Long id, Long teamId, Long userId, String idempotencyKey, LocalDateTime joinedAt) {
        MarketingGroupBuyMemberEntity entity = new MarketingGroupBuyMemberEntity();
        entity.setId(id);
        entity.setTeamId(teamId);
        entity.setUserId(userId);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setJoinedAt(joinedAt);
        groupBuyMemberRepository.save(entity);
    }

    @Override
    public List<GroupBuyTeam> findExpiredOpenTeams(LocalDateTime now, int limit) {
        return groupBuyTeamRepository
                .findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(GroupBuyStatus.OPEN, now)
                .stream()
                .limit(limit)
                .map(JpaMarketingStore::toDomain)
                .toList();
    }

    private static CouponDefinition toDomain(MarketingCouponEntity entity) {
        return new CouponDefinition(
                entity.getId(),
                entity.getCode(),
                entity.getName(),
                entity.getType(),
                entity.getThresholdAmount(),
                entity.getDiscountAmount(),
                entity.getDiscountPercent(),
                entity.getCategoryId(),
                entity.getShopId(),
                entity.getStackGroup(),
                entity.getTotalQuota(),
                entity.getClaimedCount(),
                entity.getStartTime(),
                entity.getEndTime());
    }

    private static MarketingCouponEntity toEntity(CouponDefinition coupon) {
        MarketingCouponEntity entity = new MarketingCouponEntity();
        entity.setId(coupon.id());
        entity.setCode(coupon.code());
        entity.setName(coupon.name());
        entity.setType(coupon.type());
        entity.setThresholdAmount(coupon.thresholdAmount());
        entity.setDiscountAmount(coupon.discountAmount());
        entity.setDiscountPercent(coupon.discountPercent());
        entity.setCategoryId(coupon.categoryId());
        entity.setShopId(coupon.shopId());
        entity.setStackGroup(coupon.stackGroup());
        entity.setTotalQuota(coupon.totalQuota());
        entity.setClaimedCount(coupon.claimedCount());
        entity.setStartTime(coupon.startTime());
        entity.setEndTime(coupon.endTime());
        return entity;
    }

    private static UserCoupon toDomain(MarketingUserCouponEntity entity) {
        return new UserCoupon(
                entity.getId(),
                entity.getCouponId(),
                entity.getCouponCode(),
                entity.getUserId(),
                entity.getStatus(),
                entity.getOrderId(),
                entity.getCheckoutId(),
                entity.getIdempotencyKey(),
                entity.getClaimedAt(),
                entity.getUsedAt());
    }

    private static MarketingUserCouponEntity toEntity(UserCoupon coupon) {
        MarketingUserCouponEntity entity = new MarketingUserCouponEntity();
        entity.setId(coupon.id());
        entity.setCouponId(coupon.couponId());
        entity.setCouponCode(coupon.couponCode());
        entity.setUserId(coupon.userId());
        entity.setStatus(coupon.status());
        entity.setOrderId(coupon.orderId());
        entity.setCheckoutId(coupon.checkoutId());
        entity.setIdempotencyKey(coupon.idempotencyKey());
        entity.setClaimedAt(coupon.claimedAt());
        entity.setUsedAt(coupon.usedAt());
        return entity;
    }

    private static SeckillActivity toDomain(MarketingSeckillActivityEntity entity) {
        return new SeckillActivity(
                entity.getId(),
                entity.getSkuId(),
                entity.getActivityName(),
                entity.getStockQuantity(),
                entity.getSoldQuantity(),
                entity.getPerUserLimit(),
                entity.getStartTime(),
                entity.getEndTime());
    }

    private static MarketingSeckillActivityEntity toEntity(SeckillActivity activity) {
        MarketingSeckillActivityEntity entity = new MarketingSeckillActivityEntity();
        entity.setId(activity.id());
        entity.setSkuId(activity.skuId());
        entity.setActivityName(activity.activityName());
        entity.setStockQuantity(activity.stockQuantity());
        entity.setSoldQuantity(activity.soldQuantity());
        entity.setPerUserLimit(activity.perUserLimit());
        entity.setStartTime(activity.startTime());
        entity.setEndTime(activity.endTime());
        return entity;
    }

    private static SeckillOrder toDomain(MarketingSeckillOrderEntity entity) {
        return new SeckillOrder(
                entity.getId(),
                entity.getActivityId(),
                entity.getSkuId(),
                entity.getUserId(),
                entity.getOrderId(),
                entity.getQuantity(),
                entity.getIdempotencyKey(),
                entity.getCreateTime());
    }

    private static MarketingSeckillOrderEntity toEntity(SeckillOrder order) {
        MarketingSeckillOrderEntity entity = new MarketingSeckillOrderEntity();
        entity.setId(order.id());
        entity.setActivityId(order.activityId());
        entity.setSkuId(order.skuId());
        entity.setUserId(order.userId());
        entity.setOrderId(order.orderId());
        entity.setQuantity(order.quantity());
        entity.setIdempotencyKey(order.idempotencyKey());
        entity.setCreateTime(order.createdAt());
        return entity;
    }

    private static GroupBuyActivity toDomain(MarketingGroupBuyActivityEntity entity) {
        return new GroupBuyActivity(
                entity.getId(),
                entity.getSkuId(),
                entity.getActivityName(),
                entity.getTargetSize(),
                entity.getDurationHours(),
                entity.isActive());
    }

    private static GroupBuyTeam toDomain(MarketingGroupBuyTeamEntity entity) {
        return new GroupBuyTeam(
                entity.getId(),
                entity.getActivityId(),
                entity.getSkuId(),
                entity.getLeaderUserId(),
                entity.getTargetSize(),
                entity.getJoinedCount(),
                entity.getStatus(),
                entity.getExpiresAt());
    }

    private static MarketingGroupBuyTeamEntity toEntity(GroupBuyTeam team) {
        MarketingGroupBuyTeamEntity entity = new MarketingGroupBuyTeamEntity();
        entity.setId(team.id());
        entity.setActivityId(team.activityId());
        entity.setSkuId(team.skuId());
        entity.setLeaderUserId(team.leaderUserId());
        entity.setTargetSize(team.targetSize());
        entity.setJoinedCount(team.joinedCount());
        entity.setStatus(team.status());
        entity.setExpiresAt(team.expiresAt());
        return entity;
    }
}
