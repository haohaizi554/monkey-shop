package com.example.monkey.marketing.interfaces;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.marketing.application.MarketingApplicationService;
import com.example.monkey.marketing.application.dto.CouponClaimRequestDto;
import com.example.monkey.marketing.application.dto.CouponRedeemRequestDto;
import com.example.monkey.marketing.application.dto.CouponReturnRequestDto;
import com.example.monkey.marketing.application.dto.GroupBuyJoinRequestDto;
import com.example.monkey.marketing.application.dto.MarketingPriceRequestDto;
import com.example.monkey.marketing.application.dto.SeckillRequestDto;
import com.example.monkey.shared.application.security.SessionUser;
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
        controller.createSeckillOrder(seckill, httpRequest, currentUser);
        controller.joinGroupBuy(groupBuy, currentUser);

        verify(service).claimCoupon(same(claim), eq(7L));
        verify(service).redeemCoupon(same(redeem), eq(7L));
        verify(service).returnCoupon(same(couponReturn), eq(7L));
        verify(service).quotePrice(same(quote), eq(7L));
        verify(service).createSeckillOrder(same(seckill), eq(7L), eq("127.0.0.1"));
        verify(service).joinGroupBuy(same(groupBuy), eq(7L));
    }
}
