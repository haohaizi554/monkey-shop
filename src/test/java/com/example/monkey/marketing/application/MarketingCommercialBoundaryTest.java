package com.example.monkey.marketing.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.marketing.application.dto.GroupBuyJoinRequestDto;
import com.example.monkey.marketing.application.dto.SeckillRequestDto;
import com.example.monkey.marketing.domain.GroupBuyActivity;
import com.example.monkey.marketing.domain.GroupBuyTeam;
import com.example.monkey.marketing.domain.MarketingIdempotencyStore;
import com.example.monkey.marketing.domain.MarketingLockManager;
import com.example.monkey.marketing.domain.MarketingStore;
import com.example.monkey.marketing.domain.SeckillActivity;
import com.example.monkey.marketing.domain.SeckillOrder;
import com.example.monkey.risk.domain.CommercialRiskGate;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.user.application.CaptchaService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MarketingCommercialBoundaryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void directSeckillCallerMustPassAuthoritativeActivityAndSkuToCommercialRisk() {
        MarketingStore store = mock(MarketingStore.class);
        CommercialRiskGate riskGate = mock(CommercialRiskGate.class);
        when(store.findSeckillActivity(10L))
                .thenReturn(Optional.of(new SeckillActivity(
                        10L,
                        1001L,
                        "Flash",
                        10,
                        0,
                        1,
                        Instant.parse("2025-12-31T23:59:00Z")
                                .atZone(ZoneOffset.UTC)
                                .toLocalDateTime(),
                        Instant.parse("2026-01-02T00:00:00Z")
                                .atZone(ZoneOffset.UTC)
                                .toLocalDateTime())));
        when(store.reserveSeckillStock(10L, 1)).thenReturn(true);
        when(store.saveSeckillOrder(any(SeckillOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketingApplicationService service = new MarketingApplicationService(
                store,
                (MarketingIdempotencyStore) (scope, userId, key, hash, ttl) -> true,
                new NoopLockManager(),
                (IdGenerator) () -> 1001L,
                mock(AuditService.class),
                mock(CaptchaService.class),
                CLOCK,
                riskGate);

        service.createSeckillOrder(
                new SeckillRequestDto(10L, 7L, 88L, 1, "risk-key", null), 7L, "203.0.113.10", "device-7");

        verify(riskGate)
                .requireAllowed(
                        eq(7L),
                        eq(10L),
                        eq(1001L),
                        eq(88L),
                        eq("device-7"),
                        eq("203.0.113.10"),
                        eq("marketing.seckill.order"));
    }

    @Test
    void deniedDirectSeckillCannotMutateStockOrCreateAnOrder() {
        MarketingStore store = mock(MarketingStore.class);
        CommercialRiskGate riskGate = mock(CommercialRiskGate.class);
        SeckillActivity activity = activeSeckillActivity();
        when(store.findSeckillActivity(activity.id())).thenReturn(Optional.of(activity));
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "blocked"))
                .when(riskGate)
                .requireAllowed(eq(7L), eq(activity.id()), eq(activity.skuId()), any(), any(), any(), any());
        MarketingApplicationService service = service(store, riskGate);

        assertThatThrownBy(() -> service.createSeckillOrder(
                        new SeckillRequestDto(activity.id(), 7L, 88L, 1, "blocked-key", null),
                        7L,
                        "203.0.113.10",
                        "device-7"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("blocked");

        verify(store, never()).reserveSeckillStock(any(), anyInt());
        verify(store, never()).saveSeckillOrder(any());
    }

    @Test
    void directGroupBuyCallerMustPassAuthoritativeActivityAndSkuToCommercialRisk() {
        MarketingStore store = mock(MarketingStore.class);
        CommercialRiskGate riskGate = mock(CommercialRiskGate.class);
        GroupBuyActivity activity = new GroupBuyActivity(20L, 2002L, "Group", 2, 24, true);
        when(store.findGroupBuyActivity(activity.id())).thenReturn(Optional.of(activity));
        when(store.saveGroupBuyTeam(any(GroupBuyTeam.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MarketingApplicationService service = service(store, riskGate);

        service.joinGroupBuy(
                new GroupBuyJoinRequestDto(activity.id(), 7L, null, "group-key"), 7L, "203.0.113.10", "device-7");

        verify(riskGate)
                .requireAllowed(
                        eq(7L),
                        eq(activity.id()),
                        eq(activity.skuId()),
                        eq(null),
                        eq("device-7"),
                        eq("203.0.113.10"),
                        eq("marketing.group-buy.join"));
    }

    @Test
    void deniedDirectGroupBuyCannotCreateTeamOrMember() {
        MarketingStore store = mock(MarketingStore.class);
        CommercialRiskGate riskGate = mock(CommercialRiskGate.class);
        GroupBuyActivity activity = new GroupBuyActivity(20L, 2002L, "Group", 2, 24, true);
        when(store.findGroupBuyActivity(activity.id())).thenReturn(Optional.of(activity));
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "blocked"))
                .when(riskGate)
                .requireAllowed(eq(7L), eq(activity.id()), eq(activity.skuId()), eq(null), any(), any(), any());
        MarketingApplicationService service = service(store, riskGate);

        assertThatThrownBy(() -> service.joinGroupBuy(
                        new GroupBuyJoinRequestDto(activity.id(), 7L, null, "blocked-group"),
                        7L,
                        "203.0.113.10",
                        "device-7"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("blocked");

        verify(store, never()).saveGroupBuyTeam(any());
        verify(store, never()).saveGroupBuyMember(any(), any(), any(), any(), any());
    }

    private static MarketingApplicationService service(MarketingStore store, CommercialRiskGate riskGate) {
        return new MarketingApplicationService(
                store,
                (scope, userId, key, hash, ttl) -> true,
                new NoopLockManager(),
                () -> 1001L,
                mock(AuditService.class),
                mock(CaptchaService.class),
                CLOCK,
                riskGate);
    }

    private static SeckillActivity activeSeckillActivity() {
        return new SeckillActivity(
                10L,
                1001L,
                "Flash",
                10,
                0,
                1,
                Instant.parse("2025-12-31T23:59:00Z").atZone(ZoneOffset.UTC).toLocalDateTime(),
                Instant.parse("2026-01-02T00:00:00Z").atZone(ZoneOffset.UTC).toLocalDateTime());
    }

    private static final class NoopLockManager implements MarketingLockManager {

        @Override
        public <T> T withCouponLock(Long couponId, java.util.function.Supplier<T> supplier) {
            return supplier.get();
        }

        @Override
        public <T> T withSeckillLock(Long activityId, java.util.function.Supplier<T> supplier) {
            return supplier.get();
        }

        @Override
        public <T> T withGroupBuyLock(Long teamId, java.util.function.Supplier<T> supplier) {
            return supplier.get();
        }
    }
}
