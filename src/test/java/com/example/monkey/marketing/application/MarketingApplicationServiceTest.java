package com.example.monkey.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.marketing.application.dto.CouponClaimRequestDto;
import com.example.monkey.marketing.application.dto.GroupBuyJoinRequestDto;
import com.example.monkey.marketing.application.dto.MarketingPriceRequestDto;
import com.example.monkey.marketing.application.dto.SeckillRequestDto;
import com.example.monkey.marketing.domain.CouponDefinition;
import com.example.monkey.marketing.domain.CouponType;
import com.example.monkey.marketing.domain.GroupBuyActivity;
import com.example.monkey.marketing.domain.GroupBuyStatus;
import com.example.monkey.marketing.domain.GroupBuyTeam;
import com.example.monkey.marketing.domain.MarketingLockManager;
import com.example.monkey.marketing.domain.MarketingStore;
import com.example.monkey.marketing.domain.SeckillActivity;
import com.example.monkey.marketing.domain.SeckillOrder;
import com.example.monkey.marketing.domain.UserCoupon;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.user.application.CaptchaService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class MarketingApplicationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void duplicateCouponClaimReturnsOriginalWalletEntry() {
        InMemoryMarketingStore store = seededStore();
        MarketingApplicationService service = service(store, null);

        var first = service.claimCoupon(new CouponClaimRequestDto(1L, 7L, "claim-1"));
        var duplicate = service.claimCoupon(new CouponClaimRequestDto(1L, 7L, "claim-1"));

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(store.findCoupon(1L).orElseThrow().claimedCount()).isEqualTo(1);
    }

    @Test
    void quoteStacksBestCouponPerStackGroup() {
        InMemoryMarketingStore store = seededStore();
        MarketingApplicationService service = service(store, null);

        var quote = service.quotePrice(new MarketingPriceRequestDto(
                new BigDecimal("128.00"), 7L, null, 1L, List.of("PLATFORM-20", "SHOP-10", "SHOP-5")));

        assertThat(quote.discountAmount()).isEqualByComparingTo("30.00");
        assertThat(quote.payableAmount()).isEqualByComparingTo("98.00");
        assertThat(quote.appliedCoupons()).containsExactly("PLATFORM-20", "SHOP-10");
    }

    @Test
    void oneThousandConcurrentSeckillOrdersOnlySellTenUnits() throws Exception {
        InMemoryMarketingStore store = seededStore();
        MarketingApplicationService service = service(store, null);
        int attempts = 1_000;
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(32)) {
            List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                long userId = i + 1L;
                futures.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        service.createSeckillOrder(
                                new SeckillRequestDto(10L, userId, null, 1, "flash-" + userId, null), "127.0.0.1");
                        return true;
                    } catch (BusinessException ignored) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int success = 0;
            for (var future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) {
                    success++;
                }
            }
            executor.shutdownNow();

            assertThat(success).isEqualTo(10);
            assertThat(store.findSeckillActivity(10L).orElseThrow().soldQuantity())
                    .isEqualTo(10);
        }
    }

    @Test
    void turnstileRejectsInvalidSeckillTokenWhenExternalProviderIsEnabled() {
        CaptchaService captchaService = mock(CaptchaService.class);
        when(captchaService.externalProviderEnabled()).thenReturn(true);
        when(captchaService.validate(eq(null), eq("bad-token"), eq("seckill"), any()))
                .thenReturn(false);
        MarketingApplicationService service = service(seededStore(), captchaService);

        assertThatThrownBy(() -> service.createSeckillOrder(
                        new SeckillRequestDto(10L, 1L, null, 1, "flash-1", "bad-token"), "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Human verification failed");
    }

    @Test
    void expiredOpenGroupBuyTeamsAreCancelledIdempotently() {
        InMemoryMarketingStore store = seededStore();
        store.saveGroupBuyTeam(new GroupBuyTeam(
                99L,
                20L,
                1001L,
                7L,
                2,
                1,
                GroupBuyStatus.OPEN,
                LocalDateTime.now(CLOCK).minusMinutes(1)));
        MarketingApplicationService service = service(store, null);

        assertThat(service.expireGroupBuyTeams()).isEqualTo(1);
        assertThat(service.expireGroupBuyTeams()).isZero();
        assertThat(store.findGroupBuyTeam(99L).orElseThrow().status()).isEqualTo(GroupBuyStatus.CANCELLED);
    }

    @Test
    void joiningGroupBuyMovesTeamToSucceededAtTargetSize() {
        InMemoryMarketingStore store = seededStore();
        MarketingApplicationService service = service(store, null);

        var opened = service.joinGroupBuy(new GroupBuyJoinRequestDto(20L, 7L, null, "group-1"));
        var joined = service.joinGroupBuy(new GroupBuyJoinRequestDto(20L, 8L, opened.id(), "group-2"));

        assertThat(joined.status()).isEqualTo("SUCCEEDED");
        assertThat(joined.joinedCount()).isEqualTo(2);
    }

    private static MarketingApplicationService service(InMemoryMarketingStore store, CaptchaService captchaService) {
        return new MarketingApplicationService(
                store,
                (scope, userId, idempotencyKey, requestHash, ttl) -> true,
                new InMemoryMarketingLockManager(),
                new AtomicIdGenerator(),
                mock(AuditService.class),
                captchaService,
                CLOCK);
    }

    private static InMemoryMarketingStore seededStore() {
        LocalDateTime now = LocalDateTime.now(CLOCK);
        InMemoryMarketingStore store = new InMemoryMarketingStore();
        store.coupons.put(
                1L,
                new CouponDefinition(
                        1L,
                        "PLATFORM-20",
                        "Platform coupon",
                        CouponType.THRESHOLD,
                        new BigDecimal("100.00"),
                        new BigDecimal("20.00"),
                        BigDecimal.ZERO,
                        null,
                        null,
                        "PLATFORM",
                        100,
                        0,
                        now.minusDays(1),
                        now.plusDays(1)));
        store.coupons.put(
                2L,
                new CouponDefinition(
                        2L,
                        "SHOP-10",
                        "Shop coupon",
                        CouponType.SHOP,
                        new BigDecimal("50.00"),
                        new BigDecimal("10.00"),
                        BigDecimal.ZERO,
                        null,
                        1L,
                        "SHOP",
                        100,
                        0,
                        now.minusDays(1),
                        now.plusDays(1)));
        store.coupons.put(
                3L,
                new CouponDefinition(
                        3L,
                        "SHOP-5",
                        "Small shop coupon",
                        CouponType.SHOP,
                        BigDecimal.ZERO,
                        new BigDecimal("5.00"),
                        BigDecimal.ZERO,
                        null,
                        1L,
                        "SHOP",
                        100,
                        0,
                        now.minusDays(1),
                        now.plusDays(1)));
        store.activities.put(
                10L, new SeckillActivity(10L, 1001L, "Flash sale", 10, 0, 1, now.minusMinutes(1), now.plusDays(1)));
        store.groupActivities.put(20L, new GroupBuyActivity(20L, 1001L, "Two-person group", 2, 24, true));
        return store;
    }

    private static final class InMemoryMarketingLockManager implements MarketingLockManager {
        private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

        @Override
        public <T> T withCouponLock(Long couponId, Supplier<T> supplier) {
            return withLock("coupon:" + couponId, supplier);
        }

        @Override
        public <T> T withSeckillLock(Long activityId, Supplier<T> supplier) {
            return withLock("seckill:" + activityId, supplier);
        }

        @Override
        public <T> T withGroupBuyLock(Long teamId, Supplier<T> supplier) {
            return withLock("group:" + teamId, supplier);
        }

        private <T> T withLock(String key, Supplier<T> supplier) {
            ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock());
            lock.lock();
            try {
                return supplier.get();
            } finally {
                lock.unlock();
            }
        }
    }

    private static final class InMemoryMarketingStore implements MarketingStore {
        private final Map<Long, CouponDefinition> coupons = new ConcurrentHashMap<>();
        private final Map<String, UserCoupon> userCoupons = new ConcurrentHashMap<>();
        private final Map<Long, SeckillActivity> activities = new ConcurrentHashMap<>();
        private final Map<String, SeckillOrder> seckillOrders = new ConcurrentHashMap<>();
        private final Map<Long, GroupBuyActivity> groupActivities = new ConcurrentHashMap<>();
        private final Map<Long, GroupBuyTeam> teams = new ConcurrentHashMap<>();
        private final Map<String, Long> members = new ConcurrentHashMap<>();

        @Override
        public Optional<CouponDefinition> findCoupon(Long couponId) {
            return Optional.ofNullable(coupons.get(couponId));
        }

        @Override
        public Optional<CouponDefinition> findCouponByCode(String code) {
            return coupons.values().stream()
                    .filter(coupon -> coupon.code().equals(code))
                    .findFirst();
        }

        @Override
        public CouponDefinition saveCoupon(CouponDefinition coupon) {
            coupons.put(coupon.id(), coupon);
            return coupon;
        }

        @Override
        public Optional<UserCoupon> findUserCoupon(Long userId, Long couponId) {
            return Optional.ofNullable(userCoupons.get(userId + ":" + couponId));
        }

        @Override
        public Optional<UserCoupon> findUserCouponByCode(String couponCode) {
            return userCoupons.values().stream()
                    .filter(coupon -> coupon.couponCode().equals(couponCode))
                    .findFirst();
        }

        @Override
        public UserCoupon saveUserCoupon(UserCoupon coupon) {
            userCoupons.put(coupon.userId() + ":" + coupon.couponId(), coupon);
            return coupon;
        }

        @Override
        public Optional<SeckillActivity> findSeckillActivity(Long activityId) {
            return Optional.ofNullable(activities.get(activityId));
        }

        @Override
        public SeckillActivity saveSeckillActivity(SeckillActivity activity) {
            activities.put(activity.id(), activity);
            return activity;
        }

        @Override
        public Optional<SeckillOrder> findSeckillOrder(Long activityId, Long userId, String idempotencyKey) {
            return Optional.ofNullable(seckillOrders.get(activityId + ":" + userId + ":seckill:" + idempotencyKey));
        }

        @Override
        public int purchasedQuantity(Long activityId, Long userId) {
            return seckillOrders.values().stream()
                    .filter(order -> order.activityId().equals(activityId)
                            && order.userId().equals(userId))
                    .mapToInt(SeckillOrder::quantity)
                    .sum();
        }

        @Override
        public SeckillOrder saveSeckillOrder(SeckillOrder order) {
            seckillOrders.put(order.activityId() + ":" + order.userId() + ":" + order.idempotencyKey(), order);
            return order;
        }

        @Override
        public Optional<GroupBuyActivity> findGroupBuyActivity(Long activityId) {
            return Optional.ofNullable(groupActivities.get(activityId));
        }

        @Override
        public Optional<GroupBuyTeam> findGroupBuyTeam(Long teamId) {
            return Optional.ofNullable(teams.get(teamId));
        }

        @Override
        public GroupBuyTeam saveGroupBuyTeam(GroupBuyTeam team) {
            teams.put(team.id(), team);
            return team;
        }

        @Override
        public boolean hasGroupBuyMember(Long teamId, Long userId) {
            return members.containsKey(teamId + ":" + userId);
        }

        @Override
        public void saveGroupBuyMember(
                Long id, Long teamId, Long userId, String idempotencyKey, LocalDateTime joinedAt) {
            members.put(teamId + ":" + userId, id);
        }

        @Override
        public List<GroupBuyTeam> findExpiredOpenTeams(LocalDateTime now, int limit) {
            return teams.values().stream()
                    .filter(team -> GroupBuyStatus.OPEN.equals(team.status()) && !now.isBefore(team.expiresAt()))
                    .limit(limit)
                    .toList();
        }
    }

    private static final class AtomicIdGenerator implements IdGenerator {
        private final AtomicLong next = new AtomicLong(1_000L);

        @Override
        public long nextId() {
            return next.incrementAndGet();
        }
    }
}
