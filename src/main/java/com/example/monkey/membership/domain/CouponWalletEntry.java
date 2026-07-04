package com.example.monkey.membership.domain;

import java.time.LocalDateTime;

public record CouponWalletEntry(
        Long id,
        Long couponId,
        String couponCode,
        Long userId,
        String status,
        Long orderId,
        LocalDateTime claimedAt,
        LocalDateTime usedAt) {}
