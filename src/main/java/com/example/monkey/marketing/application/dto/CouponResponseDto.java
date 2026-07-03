package com.example.monkey.marketing.application.dto;

import java.time.LocalDateTime;

public record CouponResponseDto(
        Long id,
        Long couponId,
        String couponCode,
        Long userId,
        String status,
        Long orderId,
        LocalDateTime claimedAt,
        LocalDateTime usedAt) {}
