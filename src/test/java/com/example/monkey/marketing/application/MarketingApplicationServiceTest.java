package com.example.monkey.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.marketing.application.dto.CouponClaimRequestDto;
import com.example.monkey.marketing.application.dto.CouponRedeemRequestDto;
import com.example.monkey.marketing.application.dto.CouponReturnRequestDto;
import com.example.monkey.marketing.application.dto.GroupBuyJoinRequestDto;
import com.example.monkey.marketing.application.dto.MarketingPriceLineDto;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class MarketingApplicationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void duplicateCouponClaimReturnsOriginalWalletEntry() {
        InMemoryMarketingStore store = seededStore();
        MarketingApplicationService service = service(store, null);

        var first = service.claimCoupon(new CouponClaimRequestDto(1L, "claim-1"), 7L);
        var duplicate = service.claimCoupon(new CouponClaimRequestDto(1L, "claim-1"), 7L);

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(store.findCoupon(1L).orElseThrow().claimedCount()).isEqualTo(1);
    }

    @Test
    void authenticatedCouponClaimUsesOnlyTheSessionOwner() {
        InMemoryMarketingStore store = seededStore();
        MarketingApplicationService service = service(store, null);

        var claimed = service.claimCoupon(new CouponClaimRequestDto(1L, "claim-owner"), 7L);

        assertThat(claimed.userId()).isEqualTo(7L);
        assertThat(store.findUserCoupon(7L, 1L)).isPresent();
    }

    @Test
    void authenticatedCouponMutationRejectsAnotherUsersCoupon() {
        MarketingApplicationService service = service(seededStore(), null);
        var coupon = service.claimCoupon(new CouponClaimRequestDto(1L, "claim-ownership"), 7L);

        assertThatThrownBy(() -> service.redeemCoupon(new CouponRedeemRequestDto(coupon.couponCode(), 10L), 8L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("current user");

        service.redeemCoupon(new CouponRedeemRequestDto(coupon.couponCode(), 10L), 7L);
        assertThatThrownBy(() -> service.returnCoupon(new CouponReturnRequestDto(coupon.couponCode(), 10L), 8L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("current user");
    }

    @Test
    void couponMutationRequiresAnAuthenticatedOwner() {
        MarketingApplicationService redeemService = service(seededStore(), null);
        var redeemCoupon = redeemService.claimCoupon(new CouponClaimRequestDto(1L, "claim-redeem-owner"), 7L);
        assertThatThrownBy(() ->
                        redeemService.redeemCoupon(new CouponRedeemRequestDto(redeemCoupon.couponCode(), 10L), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("current user");

        MarketingApplicationService returnService = service(seededStore(), null);
        var returnCoupon = returnService.claimCoupon(new CouponClaimRequestDto(1L, "claim-return-owner"), 7L);
        returnService.redeemCoupon(new CouponRedeemRequestDto(returnCoupon.couponCode(), 10L), 7L);
        assertThatThrownBy(() ->
                        returnService.returnCoupon(new CouponReturnRequestDto(returnCoupon.couponCode(), 10L), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("current user");
    }

    @Test
    void authenticatedMarketingRequestsRejectForeignBodyIdentity() {
        MarketingApplicationService service = service(seededStore(), null);

        assertThatThrownBy(() -> service.quotePrice(
                        new MarketingPriceRequestDto(new BigDecimal("128.00"), 999L, null, 1L, List.of()), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("current user");
        assertThatThrownBy(() -> service.createSeckillOrder(
                        new SeckillRequestDto(10L, 999L, null, 1, "foreign-seckill", null), 7L, "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("current user");
        assertThatThrownBy(() -> service.joinGroupBuy(new GroupBuyJoinRequestDto(20L, 999L, null, "foreign-group"), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("current user");
    }

    @Test
    void priceQuoteRejectsCouponNotClaimedByCurrentUser() {
        InMemoryMarketingStore store = seededStore();
        MarketingApplicationService service = service(store, null);
        service.claimCoupon(new CouponClaimRequestDto(1L, "claim-owner-quote"), 7L);

        assertThatThrownBy(() -> service.quotePrice(
                        new MarketingPriceRequestDto(new BigDecimal("128.00"), 8L, null, 1L, List.of("PLATFORM-20")),
                        8L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("current user");
    }

    @Test
    void concurrentCouponRedemptionCanBindOnlyOneOrder() throws Exception {
        InMemoryMarketingStore store = seededStore();
        MarketingApplicationService service = service(store, null);
        var coupon = service.claimCoupon(new CouponClaimRequestDto(1L, "claim-concurrent"), 7L);
        int attempts = 16;
        store.synchronizeNextCouponReads(attempts);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong successes = new AtomicLong();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        try (var executor = Executors.newFixedThreadPool(attempts)) {
            for (int index = 0; index < attempts; index++) {
                long orderId = 10_000L + index;
                executor.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        service.redeemCoupon(new CouponRedeemRequestDto(coupon.couponCode(), orderId), 7L);
                        successes.incrementAndGet();
                    } catch (BusinessException expectedConflict) {
                        // Only the compare-and-set winner may bind the coupon.
                    } catch (Throwable throwable) {
                        unexpected.compareAndSet(null, throwable);
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(unexpected.get()).isNull();
        assertThat(successes.get()).isEqualTo(1L);
    }

    @Test
    void checkoutRedemptionIsIdempotentAndRejectsAnotherCheckout() {
        InMemoryMarketingStore store = seededStore();
        MarketingApplicationService service = service(store, null);
        var coupon = service.claimCoupon(new CouponClaimRequestDto(1L, "claim-checkout"), 7L);

        service.redeemForCheckout(7L, 101L, List.of(coupon.couponCode()));
        service.redeemForCheckout(7L, 101L, List.of(coupon.couponCode()));

        UserCoupon redeemed = store.findUserCoupon(7L, 1L).orElseThrow();
        assertThat(redeemed.status()).isEqualTo(com.example.monkey.marketing.domain.CouponStatus.REDEEMED);
        assertThat(redeemed.checkoutId()).isEqualTo(101L);
        assertThatThrownBy(() -> service.redeemForCheckout(7L, 102L, List.of(coupon.couponCode())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("another transaction");
    }

    @Test
    void lateCheckoutReturnCannotReleaseCouponReusedByAnotherCheckout() {
        InMemoryMarketingStore store = seededStore();
        MarketingApplicationService service = service(store, null);
        var coupon = service.claimCoupon(new CouponClaimRequestDto(1L, "claim-return-checkout"), 7L);

        service.redeemForCheckout(7L, 101L, List.of(coupon.couponCode()));
        service.returnForCheckout(7L, 101L, "ORDER_CANCELLED");
        service.returnForCheckout(7L, 101L, "ORDER_CANCELLED");
        service.redeemForCheckout(7L, 102L, List.of(coupon.couponCode()));
        service.returnForCheckout(7L, 101L, "LATE_RETRY");

        UserCoupon rebound = store.findUserCoupon(7L, 1L).orElseThrow();
        assertThat(rebound.status()).isEqualTo(com.example.monkey.marketing.domain.CouponStatus.REDEEMED);
        assertThat(rebound.checkoutId()).isEqualTo(102L);
    }

    @Test
    void quoteStacksBestCouponPerStackGroup() {
        InMemoryMarketingStore store = seededStore();
        MarketingApplicationService service = service(store, null);
        service.claimCoupon(new CouponClaimRequestDto(1L, "quote-platform"), 7L);
        service.claimCoupon(new CouponClaimRequestDto(2L, "quote-shop-10"), 7L);
        service.claimCoupon(new CouponClaimRequestDto(3L, "quote-shop-5"), 7L);

        var quote = service.quotePrice(new MarketingPriceRequestDto(
                new BigDecimal("128.00"), 7L, null, 1L, List.of("PLATFORM-20", "SHOP-10", "SHOP-5")));

        assertThat(quote.discountAmount()).isEqualByComparingTo("30.00");
        assertThat(quote.payableAmount()).isEqualByComparingTo("98.00");
        assertThat(quote.appliedCoupons()).containsExactly("PLATFORM-20", "SHOP-10");
    }

    @Test
    void categoryCouponAllocatesOnlyToEligiblePriceLines() {
        InMemoryMarketingStore store = seededStore();
        MarketingApplicationService service = service(store, null);
        service.claimCoupon(new CouponClaimRequestDto(4L, "quote-category"), 7L);

        var quote = service.quoteStorePrice(new MarketingPriceRequestDto(
                new BigDecimal("150.00"),
                7L,
                null,
                1L,
                List.of("CATEGORY-20"),
                List.of(
                        new MarketingPriceLineDto(1001L, new BigDecimal("100.00"), 11L, 1L),
                        new MarketingPriceLineDto(1002L, new BigDecimal("50.00"), 12L, 1L))));

        assertThat(quote.discountAmount()).isEqualByComparingTo("20.00");
        assertThat(quote.appliedCoupons()).containsExactly("CATEGORY-20");
        assertThat(quote.allocations())
                .extracting(allocation -> allocation.lineId() + ":" + allocation.discountAmount())
                .containsExactly("1001:20.00", "1002:0.00");
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
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        return false;
                    }
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
        store.coupons.put(
                4L,
                new CouponDefinition(
                        4L,
                        "CATEGORY-20",
                        "Category coupon",
                        CouponType.CATEGORY,
                        new BigDecimal("50.00"),
                        new BigDecimal("20.00"),
                        BigDecimal.ZERO,
                        11L,
                        null,
                        "CATEGORY",
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
        private volatile CountDownLatch synchronizedCouponReads;

        private void synchronizeNextCouponReads(int readers) {
            synchronizedCouponReads = new CountDownLatch(readers);
        }

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
        public Optional<UserCoupon> findUserCouponByCode(Long userId, String couponCode) {
            Optional<UserCoupon> result = userCoupons.values().stream()
                    .filter(userCoupon -> userCoupon.userId().equals(userId)
                            && userCoupon.couponCode().equals(couponCode))
                    .findFirst();
            CountDownLatch reads = synchronizedCouponReads;
            if (reads != null) {
                reads.countDown();
                try {
                    if (!reads.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out synchronizing coupon reads");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted synchronizing coupon reads", exception);
                }
            }
            return result;
        }

        @Override
        public UserCoupon saveUserCoupon(UserCoupon coupon) {
            userCoupons.put(coupon.userId() + ":" + coupon.couponId(), coupon);
            return coupon;
        }

        @Override
        public synchronized boolean redeemUserCouponForOrder(
                Long userId, String couponCode, Long orderId, LocalDateTime usedAt) {
            Optional<UserCoupon> existing = userCouponByCode(userId, couponCode);
            if (existing.isEmpty()
                    || !com.example.monkey.marketing.domain.CouponStatus.CLAIMED.equals(
                            existing.orElseThrow().status())) {
                return false;
            }
            UserCoupon redeemed = existing.orElseThrow().redeem(orderId, usedAt);
            userCoupons.put(redeemed.userId() + ":" + redeemed.couponId(), redeemed);
            return true;
        }

        @Override
        public synchronized boolean returnUserCouponForOrder(Long userId, String couponCode, Long orderId) {
            Optional<UserCoupon> existing = userCouponByCode(userId, couponCode);
            if (existing.isEmpty() || !existing.orElseThrow().isRedeemed()) {
                return false;
            }
            UserCoupon returned = existing.orElseThrow().returnToWallet(orderId);
            userCoupons.put(returned.userId() + ":" + returned.couponId(), returned);
            return true;
        }

        @Override
        public synchronized boolean redeemUserCouponForCheckout(
                Long userId, String couponCode, Long checkoutId, LocalDateTime usedAt) {
            Optional<UserCoupon> existing = userCouponByCode(userId, couponCode);
            if (existing.isEmpty()
                    || !com.example.monkey.marketing.domain.CouponStatus.CLAIMED.equals(
                            existing.orElseThrow().status())) {
                return false;
            }
            UserCoupon coupon = existing.orElseThrow();
            UserCoupon redeemed = new UserCoupon(
                    coupon.id(),
                    coupon.couponId(),
                    coupon.couponCode(),
                    coupon.userId(),
                    com.example.monkey.marketing.domain.CouponStatus.REDEEMED,
                    null,
                    checkoutId,
                    coupon.idempotencyKey(),
                    coupon.claimedAt(),
                    usedAt);
            userCoupons.put(redeemed.userId() + ":" + redeemed.couponId(), redeemed);
            return true;
        }

        @Override
        public synchronized int returnUserCouponsForCheckout(Long userId, Long checkoutId) {
            int returned = 0;
            for (Map.Entry<String, UserCoupon> entry : userCoupons.entrySet()) {
                UserCoupon coupon = entry.getValue();
                if (coupon.userId().equals(userId) && coupon.isRedeemed() && checkoutId.equals(coupon.checkoutId())) {
                    entry.setValue(new UserCoupon(
                            coupon.id(),
                            coupon.couponId(),
                            coupon.couponCode(),
                            coupon.userId(),
                            com.example.monkey.marketing.domain.CouponStatus.CLAIMED,
                            null,
                            checkoutId,
                            coupon.idempotencyKey(),
                            coupon.claimedAt(),
                            coupon.usedAt()));
                    returned++;
                }
            }
            return returned;
        }

        private Optional<UserCoupon> userCouponByCode(Long userId, String couponCode) {
            return userCoupons.values().stream()
                    .filter(userCoupon -> userCoupon.userId().equals(userId)
                            && userCoupon.couponCode().equals(couponCode))
                    .findFirst();
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
