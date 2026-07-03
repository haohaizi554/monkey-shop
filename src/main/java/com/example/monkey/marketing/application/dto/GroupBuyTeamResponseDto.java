package com.example.monkey.marketing.application.dto;

import java.time.LocalDateTime;

public record GroupBuyTeamResponseDto(
        Long id,
        Long activityId,
        Long skuId,
        Long leaderUserId,
        int targetSize,
        int joinedCount,
        String status,
        LocalDateTime expiresAt) {}
