package com.example.monkey.marketing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.marketing.domain.CouponDefinition;
import com.example.monkey.marketing.domain.CouponStatus;
import com.example.monkey.marketing.domain.CouponType;
import com.example.monkey.marketing.domain.GroupBuyStatus;
import com.example.monkey.marketing.domain.GroupBuyTeam;
import com.example.monkey.marketing.domain.SeckillActivity;
import com.example.monkey.marketing.domain.SeckillOrder;
import com.example.monkey.marketing.domain.UserCoupon;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class MarketingInfrastructureTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Test
    void jpaMarketingStoreMapsCouponUserCouponSeckillAndGroupBuyModels() {
        MarketingCouponRepository couponRepository = mock(MarketingCouponRepository.class);
        MarketingUserCouponRepository userCouponRepository = mock(MarketingUserCouponRepository.class);
        MarketingSeckillActivityRepository seckillActivityRepository = mock(MarketingSeckillActivityRepository.class);
        MarketingSeckillOrderRepository seckillOrderRepository = mock(MarketingSeckillOrderRepository.class);
        MarketingGroupBuyActivityRepository groupBuyActivityRepository =
                mock(MarketingGroupBuyActivityRepository.class);
        MarketingGroupBuyTeamRepository groupBuyTeamRepository = mock(MarketingGroupBuyTeamRepository.class);
        MarketingGroupBuyMemberRepository groupBuyMemberRepository = mock(MarketingGroupBuyMemberRepository.class);
        when(couponRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userCouponRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(seckillActivityRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(seckillOrderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(groupBuyTeamRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(couponRepository.findById(1L)).thenReturn(Optional.of(couponEntity()));
        when(couponRepository.findByCode("PLATFORM-20")).thenReturn(Optional.of(couponEntity()));
        when(userCouponRepository.findByUserIdAndCouponId(7L, 1L)).thenReturn(Optional.of(userCouponEntity()));
        when(userCouponRepository.findByUserIdAndCouponCode(7L, "PLATFORM-20"))
                .thenReturn(Optional.of(userCouponEntity()));
        when(seckillActivityRepository.findById(10L)).thenReturn(Optional.of(seckillActivityEntity()));
        when(seckillOrderRepository.findByActivityIdAndUserIdAndIdempotencyKey(10L, 7L, "seckill:key"))
                .thenReturn(Optional.of(seckillOrderEntity()));
        when(seckillOrderRepository.purchasedQuantity(10L, 7L)).thenReturn(1);
        when(groupBuyActivityRepository.findById(20L)).thenReturn(Optional.of(groupBuyActivityEntity()));
        when(groupBuyTeamRepository.findById(30L)).thenReturn(Optional.of(groupBuyTeamEntity()));
        when(groupBuyTeamRepository.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(GroupBuyStatus.OPEN, NOW))
                .thenReturn(List.of(groupBuyTeamEntity()));
        when(groupBuyMemberRepository.existsByTeamIdAndUserId(30L, 7L)).thenReturn(true);
        JpaMarketingStore store = new JpaMarketingStore(
                couponRepository,
                userCouponRepository,
                seckillActivityRepository,
                seckillOrderRepository,
                groupBuyActivityRepository,
                groupBuyTeamRepository,
                groupBuyMemberRepository);

        assertThat(store.findCoupon(1L).orElseThrow().code()).isEqualTo("PLATFORM-20");
        assertThat(store.findCouponByCode("PLATFORM-20").orElseThrow().claimedCount())
                .isEqualTo(1);
        assertThat(store.saveCoupon(coupon()).stackGroup()).isEqualTo("PLATFORM");
        assertThat(store.findUserCoupon(7L, 1L).orElseThrow().status()).isEqualTo(CouponStatus.CLAIMED);
        assertThat(store.findUserCouponByCode(7L, "PLATFORM-20").orElseThrow().userId())
                .isEqualTo(7L);
        assertThat(store.saveUserCoupon(userCoupon()).couponCode()).isEqualTo("PLATFORM-20");
        assertThat(store.findSeckillActivity(10L).orElseThrow().soldQuantity()).isEqualTo(1);
        assertThat(store.saveSeckillActivity(seckillActivity()).stockQuantity()).isEqualTo(10);
        assertThat(store.findSeckillOrder(10L, 7L, "key").orElseThrow().quantity())
                .isEqualTo(1);
        assertThat(store.purchasedQuantity(10L, 7L)).isEqualTo(1);
        assertThat(store.saveSeckillOrder(seckillOrder()).idempotencyKey()).isEqualTo("seckill:key");
        assertThat(store.findGroupBuyActivity(20L).orElseThrow().targetSize()).isEqualTo(2);
        assertThat(store.findGroupBuyTeam(30L).orElseThrow().status()).isEqualTo(GroupBuyStatus.OPEN);
        assertThat(store.saveGroupBuyTeam(groupBuyTeam()).joinedCount()).isEqualTo(1);
        assertThat(store.hasGroupBuyMember(30L, 7L)).isTrue();
        store.saveGroupBuyMember(99L, 30L, 7L, "group:key", NOW);
        assertThat(store.findExpiredOpenTeams(NOW, 100)).hasSize(1);
    }

    @Test
    void redisIdempotencyStoreAllowsFallbackWhenRedisIsUnavailable() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        RedisMarketingIdempotencyStore store = new RedisMarketingIdempotencyStore(provider);

        assertThat(store.reserve("seckill:1", 7L, "key", "request", Duration.ofMinutes(5)))
                .isTrue();
    }

    @Test
    void redissonMarketingLockManagerFallsBackWhenClientIsUnavailable() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        RedissonMarketingLockManager lockManager = new RedissonMarketingLockManager(provider);

        assertThat(lockManager.withCouponLock(1L, () -> "coupon")).isEqualTo("coupon");
        assertThat(lockManager.withSeckillLock(2L, () -> "seckill")).isEqualTo("seckill");
        assertThat(lockManager.withGroupBuyLock(3L, () -> "group")).isEqualTo("group");
    }

    private static CouponDefinition coupon() {
        return new CouponDefinition(
                1L,
                "PLATFORM-20",
                "Platform",
                CouponType.THRESHOLD,
                new BigDecimal("100.00"),
                new BigDecimal("20.00"),
                BigDecimal.ZERO,
                null,
                null,
                "PLATFORM",
                100,
                1,
                NOW.minusDays(1),
                NOW.plusDays(1));
    }

    private static UserCoupon userCoupon() {
        return new UserCoupon(2L, 1L, "PLATFORM-20", 7L, CouponStatus.CLAIMED, null, null, "coupon:key", NOW, null);
    }

    private static SeckillActivity seckillActivity() {
        return new SeckillActivity(10L, 1001L, "Flash", 10, 1, 1, NOW.minusDays(1), NOW.plusDays(1));
    }

    private static SeckillOrder seckillOrder() {
        return new SeckillOrder(11L, 10L, 1001L, 7L, 88L, 1, "seckill:key", NOW);
    }

    private static GroupBuyTeam groupBuyTeam() {
        return new GroupBuyTeam(30L, 20L, 1001L, 7L, 2, 1, GroupBuyStatus.OPEN, NOW.minusMinutes(1));
    }

    private static MarketingCouponEntity couponEntity() {
        MarketingCouponEntity entity = new MarketingCouponEntity();
        entity.setId(1L);
        entity.setCode("PLATFORM-20");
        entity.setName("Platform");
        entity.setType(CouponType.THRESHOLD);
        entity.setThresholdAmount(new BigDecimal("100.00"));
        entity.setDiscountAmount(new BigDecimal("20.00"));
        entity.setDiscountPercent(BigDecimal.ZERO);
        entity.setCategoryId(null);
        entity.setShopId(null);
        entity.setStackGroup("PLATFORM");
        entity.setTotalQuota(100);
        entity.setClaimedCount(1);
        entity.setStartTime(NOW.minusDays(1));
        entity.setEndTime(NOW.plusDays(1));
        return entity;
    }

    private static MarketingUserCouponEntity userCouponEntity() {
        MarketingUserCouponEntity entity = new MarketingUserCouponEntity();
        entity.setId(2L);
        entity.setCouponId(1L);
        entity.setCouponCode("PLATFORM-20");
        entity.setUserId(7L);
        entity.setStatus(CouponStatus.CLAIMED);
        entity.setOrderId(null);
        entity.setCheckoutId(null);
        entity.setIdempotencyKey("coupon:key");
        entity.setClaimedAt(NOW);
        entity.setUsedAt(null);
        return entity;
    }

    private static MarketingSeckillActivityEntity seckillActivityEntity() {
        MarketingSeckillActivityEntity entity = new MarketingSeckillActivityEntity();
        entity.setId(10L);
        entity.setSkuId(1001L);
        entity.setActivityName("Flash");
        entity.setStockQuantity(10);
        entity.setSoldQuantity(1);
        entity.setPerUserLimit(1);
        entity.setStartTime(NOW.minusDays(1));
        entity.setEndTime(NOW.plusDays(1));
        return entity;
    }

    private static MarketingSeckillOrderEntity seckillOrderEntity() {
        MarketingSeckillOrderEntity entity = new MarketingSeckillOrderEntity();
        entity.setId(11L);
        entity.setActivityId(10L);
        entity.setSkuId(1001L);
        entity.setUserId(7L);
        entity.setOrderId(88L);
        entity.setQuantity(1);
        entity.setIdempotencyKey("seckill:key");
        entity.setCreateTime(NOW);
        return entity;
    }

    private static MarketingGroupBuyActivityEntity groupBuyActivityEntity() {
        MarketingGroupBuyActivityEntity entity = new MarketingGroupBuyActivityEntity();
        entity.setId(20L);
        entity.setSkuId(1001L);
        entity.setActivityName("Group");
        entity.setTargetSize(2);
        entity.setDurationHours(24);
        entity.setActive(true);
        return entity;
    }

    private static MarketingGroupBuyTeamEntity groupBuyTeamEntity() {
        MarketingGroupBuyTeamEntity entity = new MarketingGroupBuyTeamEntity();
        entity.setId(30L);
        entity.setActivityId(20L);
        entity.setSkuId(1001L);
        entity.setLeaderUserId(7L);
        entity.setTargetSize(2);
        entity.setJoinedCount(1);
        entity.setStatus(GroupBuyStatus.OPEN);
        entity.setExpiresAt(NOW.minusMinutes(1));
        return entity;
    }
}
