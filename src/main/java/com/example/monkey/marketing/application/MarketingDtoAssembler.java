package com.example.monkey.marketing.application;

import com.example.monkey.marketing.application.dto.CouponResponseDto;
import com.example.monkey.marketing.application.dto.GroupBuyTeamResponseDto;
import com.example.monkey.marketing.application.dto.MarketingPriceQuoteDto;
import com.example.monkey.marketing.application.dto.SeckillOrderResponseDto;
import com.example.monkey.marketing.domain.GroupBuyTeam;
import com.example.monkey.marketing.domain.MarketingPriceQuote;
import com.example.monkey.marketing.domain.SeckillOrder;
import com.example.monkey.marketing.domain.UserCoupon;

final class MarketingDtoAssembler {

    private MarketingDtoAssembler() {}

    static CouponResponseDto toResponse(UserCoupon coupon) {
        return new CouponResponseDto(
                coupon.id(),
                coupon.couponId(),
                coupon.couponCode(),
                coupon.userId(),
                coupon.status().name(),
                coupon.orderId(),
                coupon.checkoutId(),
                coupon.claimedAt(),
                coupon.usedAt());
    }

    static MarketingPriceQuoteDto toResponse(MarketingPriceQuote quote) {
        return new MarketingPriceQuoteDto(
                quote.originalAmount(), quote.discountAmount(), quote.payableAmount(), quote.appliedCoupons());
    }

    static SeckillOrderResponseDto toResponse(SeckillOrder order) {
        return new SeckillOrderResponseDto(
                order.id(),
                order.activityId(),
                order.skuId(),
                order.userId(),
                order.orderId(),
                order.quantity(),
                order.idempotencyKey(),
                order.createdAt());
    }

    static GroupBuyTeamResponseDto toResponse(GroupBuyTeam team) {
        return new GroupBuyTeamResponseDto(
                team.id(),
                team.activityId(),
                team.skuId(),
                team.leaderUserId(),
                team.targetSize(),
                team.joinedCount(),
                team.status().name(),
                team.expiresAt());
    }
}
