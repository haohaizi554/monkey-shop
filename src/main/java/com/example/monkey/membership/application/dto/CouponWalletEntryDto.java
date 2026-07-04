package com.example.monkey.membership.application.dto;

import java.time.LocalDateTime;

public record CouponWalletEntryDto(
        Long id,
        Long couponId,
        String couponCode,
        String status,
        Long orderId,
        LocalDateTime claimedAt,
        LocalDateTime usedAt) {}
