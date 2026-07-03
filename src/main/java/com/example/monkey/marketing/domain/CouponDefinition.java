package com.example.monkey.marketing.domain;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Objects;

public record CouponDefinition(
        Long id,
        String code,
        String name,
        CouponType type,
        BigDecimal thresholdAmount,
        BigDecimal discountAmount,
        BigDecimal discountPercent,
        Long categoryId,
        Long shopId,
        String stackGroup,
        int totalQuota,
        int claimedCount,
        LocalDateTime startTime,
        LocalDateTime endTime) {

    public CouponDefinition {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(code, "code is required");
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(startTime, "startTime is required");
        Objects.requireNonNull(endTime, "endTime is required");
        thresholdAmount = moneyOrZero(thresholdAmount);
        discountAmount = moneyOrZero(discountAmount);
        discountPercent = discountPercent == null ? BigDecimal.ZERO : discountPercent;
        stackGroup = stackGroup == null || stackGroup.isBlank()
                ? "PLATFORM"
                : stackGroup.trim().toUpperCase();
        if (totalQuota < 0 || claimedCount < 0 || claimedCount > totalQuota) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid coupon quota");
        }
    }

    public CouponDefinition reserveClaim(LocalDateTime now) {
        if (now.isBefore(startTime) || now.isAfter(endTime)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Coupon is not active");
        }
        if (claimedCount >= totalQuota) {
            throw new BusinessException(ErrorCode.OUT_OF_STOCK, "Coupon quota exhausted");
        }
        return new CouponDefinition(
                id,
                code,
                name,
                type,
                thresholdAmount,
                discountAmount,
                discountPercent,
                categoryId,
                shopId,
                stackGroup,
                totalQuota,
                claimedCount + 1,
                startTime,
                endTime);
    }

    public boolean matches(Long orderCategoryId, Long orderShopId) {
        return switch (type) {
            case CATEGORY -> categoryId != null && categoryId.equals(orderCategoryId);
            case SHOP -> shopId != null && shopId.equals(orderShopId);
            default -> true;
        };
    }

    public BigDecimal discountFor(BigDecimal orderAmount) {
        BigDecimal amount = moneyOrZero(orderAmount);
        if (amount.compareTo(thresholdAmount) < 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal discount = CouponType.PERCENT.equals(type) ? amount.multiply(discountPercent) : discountAmount;
        if (discount.compareTo(amount) > 0) {
            return amount;
        }
        return discount.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal moneyOrZero(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP);
    }
}
