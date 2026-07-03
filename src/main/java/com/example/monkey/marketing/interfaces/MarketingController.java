package com.example.monkey.marketing.interfaces;

import com.example.monkey.marketing.application.MarketingApplicationService;
import com.example.monkey.marketing.application.dto.CouponClaimRequestDto;
import com.example.monkey.marketing.application.dto.CouponRedeemRequestDto;
import com.example.monkey.marketing.application.dto.CouponResponseDto;
import com.example.monkey.marketing.application.dto.CouponReturnRequestDto;
import com.example.monkey.marketing.application.dto.GroupBuyJoinRequestDto;
import com.example.monkey.marketing.application.dto.GroupBuyTeamResponseDto;
import com.example.monkey.marketing.application.dto.MarketingPriceQuoteDto;
import com.example.monkey.marketing.application.dto.MarketingPriceRequestDto;
import com.example.monkey.marketing.application.dto.SeckillOrderResponseDto;
import com.example.monkey.marketing.application.dto.SeckillRequestDto;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.shared.interfaces.web.ClientIps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/marketing", "/api/v1/marketing"})
public class MarketingController {

    private final MarketingApplicationService marketingApplicationService;

    public MarketingController(MarketingApplicationService marketingApplicationService) {
        this.marketingApplicationService = marketingApplicationService;
    }

    @PostMapping("/coupons/claim")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public Result<CouponResponseDto> claimCoupon(@Valid @RequestBody CouponClaimRequestDto request) {
        return Result.success(marketingApplicationService.claimCoupon(request));
    }

    @PostMapping("/coupons/redeem")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public Result<CouponResponseDto> redeemCoupon(@Valid @RequestBody CouponRedeemRequestDto request) {
        return Result.success(marketingApplicationService.redeemCoupon(request));
    }

    @PostMapping("/coupons/return")
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ORDER_MANAGE')")
    public Result<CouponResponseDto> returnCoupon(@Valid @RequestBody CouponReturnRequestDto request) {
        return Result.success(marketingApplicationService.returnCoupon(request));
    }

    @PostMapping("/price/quote")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public Result<MarketingPriceQuoteDto> quotePrice(@Valid @RequestBody MarketingPriceRequestDto request) {
        return Result.success(marketingApplicationService.quotePrice(request));
    }

    @PostMapping("/seckill-orders")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public Result<SeckillOrderResponseDto> createSeckillOrder(
            @Valid @RequestBody SeckillRequestDto request, HttpServletRequest httpRequest) {
        return Result.success(marketingApplicationService.createSeckillOrder(request, ClientIps.resolve(httpRequest)));
    }

    @PostMapping("/group-buy/join")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public Result<GroupBuyTeamResponseDto> joinGroupBuy(@Valid @RequestBody GroupBuyJoinRequestDto request) {
        return Result.success(marketingApplicationService.joinGroupBuy(request));
    }
}
