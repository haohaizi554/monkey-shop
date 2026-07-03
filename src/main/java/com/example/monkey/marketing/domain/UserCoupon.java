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
        if (CouponStatus.USED.equals(status) && Objects.equals(orderId, targetOrderId)) {
            return this;
        }
        if (!CouponStatus.CLAIMED.equals(status) && !CouponStatus.RETURNED.equals(status)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Coupon cannot be redeemed from " + status);
        }
        return new UserCoupon(
                id, couponId, couponCode, userId, CouponStatus.USED, targetOrderId, idempotencyKey, claimedAt, now);
    }

    public UserCoupon returnToWallet(Long targetOrderId) {
        if (CouponStatus.RETURNED.equals(status)) {
            return this;
        }
        if (!CouponStatus.USED.equals(status) || !Objects.equals(orderId, targetOrderId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Coupon cannot be returned");
        }
        return new UserCoupon(
                id, couponId, couponCode, userId, CouponStatus.RETURNED, null, idempotencyKey, claimedAt, usedAt);
    }
}
