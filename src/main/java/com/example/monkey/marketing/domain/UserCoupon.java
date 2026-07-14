package com.example.monkey.marketing.domain;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.Objects;

public record UserCoupon(
        Long id,
        Long couponId,
        String couponCode,
        Long userId,
        CouponStatus status,
        Long orderId,
        Long checkoutId,
        String idempotencyKey,
        LocalDateTime claimedAt,
        LocalDateTime usedAt) {

    public UserCoupon {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(couponId, "couponId is required");
        Objects.requireNonNull(couponCode, "couponCode is required");
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey is required");
        Objects.requireNonNull(claimedAt, "claimedAt is required");
    }

    public UserCoupon redeem(Long targetOrderId, LocalDateTime now) {
        if (isRedeemed() && Objects.equals(orderId, targetOrderId) && checkoutId == null) {
            return this;
        }
        if (!CouponStatus.CLAIMED.equals(status) && !CouponStatus.RETURNED.equals(status)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Coupon cannot be redeemed from " + status);
        }
        return new UserCoupon(
                id,
                couponId,
                couponCode,
                userId,
                CouponStatus.REDEEMED,
                targetOrderId,
                null,
                idempotencyKey,
                claimedAt,
                now);
    }

    public UserCoupon returnToWallet(Long targetOrderId) {
        if (CouponStatus.CLAIMED.equals(status) && Objects.equals(orderId, targetOrderId) && checkoutId == null) {
            return this;
        }
        if (!isRedeemed() || !Objects.equals(orderId, targetOrderId) || checkoutId != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "Coupon cannot be returned");
        }
        return new UserCoupon(
                id,
                couponId,
                couponCode,
                userId,
                CouponStatus.CLAIMED,
                targetOrderId,
                null,
                idempotencyKey,
                claimedAt,
                usedAt);
    }

    public boolean isRedeemed() {
        return CouponStatus.REDEEMED.equals(status) || CouponStatus.USED.equals(status);
    }
}
