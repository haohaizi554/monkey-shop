package com.example.monkey.marketing.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record GroupBuyJoinRequestDto(
        @NotNull Long activityId,
        Long userId,
        Long teamId,
        @NotBlank String idempotencyKey) {

    public GroupBuyJoinRequestDto withUserId(Long newUserId) {
        return new GroupBuyJoinRequestDto(activityId, newUserId, teamId, idempotencyKey);
    }
}
