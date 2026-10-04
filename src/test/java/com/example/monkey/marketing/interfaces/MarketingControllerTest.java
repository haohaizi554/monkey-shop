package com.example.monkey.marketing.interfaces;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.monkey.marketing.application.MarketingApplicationService;
import com.example.monkey.marketing.application.dto.CouponClaimRequestDto;
import com.example.monkey.marketing.application.dto.CouponRedeemRequestDto;
import com.example.monkey.marketing.application.dto.CouponReturnRequestDto;
import com.example.monkey.marketing.application.dto.GroupBuyJoinRequestDto;
import com.example.monkey.marketing.application.dto.MarketingPriceRequestDto;
import com.example.monkey.marketing.application.dto.SeckillRequestDto;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketingControllerTest {

    @Test
    void consumerActionsAlwaysPassTheAuthenticatedOwnerToTheApplicationService() {
        MarketingApplicationService service = mock(MarketingApplicationService.class);
        MarketingController controller = new MarketingController(service);
        SessionUser currentUser = new SessionUser(7L, "USER");
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");

        CouponClaimRequestDto claim = new CouponClaimRequestDto(1L, "claim-1");
        CouponRedeemRequestDto redeem = new CouponRedeemRequestDto("COUPON-1", 101L);
        CouponReturnRequestDto couponReturn = new CouponReturnRequestDto("COUPON-1", 101L);
        MarketingPriceRequestDto quote =
                new MarketingPriceRequestDto(new BigDecimal("128.00"), 999L, null, 1L, List.of());
        SeckillRequestDto seckill = new SeckillRequestDto(10L, 999L, null, 1, "seckill-1", null);
        GroupBuyJoinRequestDto groupBuy = new GroupBuyJoinRequestDto(20L, 999L, null, "group-1");

        controller.claimCoupon(claim, currentUser);
        controller.redeemCoupon(redeem, currentUser);
        controller.returnCoupon(couponReturn, currentUser);
        controller.quotePrice(quote, currentUser);
        controller.createSeckillOrder(seckill, "device-1", httpRequest, currentUser);
        controller.joinGroupBuy(groupBuy, "device-1", httpRequest, currentUser);

        verify(service).claimCoupon(same(claim), eq(7L));
        verify(service).redeemCoupon(same(redeem), eq(7L));
        verify(service).returnCoupon(same(couponReturn), eq(7L));
        verify(service).quotePrice(same(quote), eq(7L));
        verify(service).createSeckillOrder(same(seckill), eq(7L), eq("127.0.0.1"), eq("device-1"));
        verify(service).joinGroupBuy(same(groupBuy), eq(7L), eq("127.0.0.1"), eq("device-1"));
        verifyNoMoreInteractions(service);
    }

    @Test
    void seckillPassesOnlyHttpSignalsAndAuthenticatedIdentityToTheService() {
        MarketingApplicationService service = mock(MarketingApplicationService.class);
        MarketingController controller = new MarketingController(service);
        SessionUser currentUser = new SessionUser(7L, "USER");
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        SeckillRequestDto request = new SeckillRequestDto(10L, null, null, 2, "seckill-1", null);

        controller.createSeckillOrder(request, "device-1", httpRequest, currentUser);

        verify(service).createSeckillOrder(same(request), eq(7L), eq("127.0.0.1"), eq("device-1"));
    }

    @Test
    void groupBuyPassesOnlyHttpSignalsAndAuthenticatedIdentityToTheService() {
        MarketingApplicationService service = mock(MarketingApplicationService.class);
        MarketingController controller = new MarketingController(service);
        SessionUser currentUser = new SessionUser(7L, "USER");
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        GroupBuyJoinRequestDto request = new GroupBuyJoinRequestDto(20L, null, 301L, "group-1");

        controller.joinGroupBuy(request, "device-1", httpRequest, currentUser);

        verify(service).joinGroupBuy(same(request), eq(7L), eq("127.0.0.1"), eq("device-1"));
    }

    @Test
    void publicSeckillRejectsCallerSuppliedOrderBeforeCallingTheApplicationService() {
        MarketingApplicationService service = mock(MarketingApplicationService.class);
        MarketingController controller = new MarketingController(service);
        SessionUser currentUser = new SessionUser(7L, "USER");
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        SeckillRequestDto request = new SeckillRequestDto(10L, null, 101L, 1, "seckill-1", null);

        assertThatThrownBy(() -> controller.createSeckillOrder(request, "device-1", httpRequest, currentUser))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verifyNoInteractions(service);
    }
}
