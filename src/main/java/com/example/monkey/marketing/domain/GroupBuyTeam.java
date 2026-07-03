package com.example.monkey.marketing.domain;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.Objects;

public record GroupBuyTeam(
        Long id,
        Long activityId,
        Long skuId,
        Long leaderUserId,
        int targetSize,
        int joinedCount,
        GroupBuyStatus status,
        LocalDateTime expiresAt) {

    public GroupBuyTeam {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(activityId, "activityId is required");
        Objects.requireNonNull(skuId, "skuId is required");
        Objects.requireNonNull(leaderUserId, "leaderUserId is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        if (targetSize < 2 || joinedCount < 1 || joinedCount > targetSize) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid group-buy team");
        }
    }

    public GroupBuyTeam join() {
        if (!GroupBuyStatus.OPEN.equals(status)) {
            return this;
        }
        int nextCount = joinedCount + 1;
        GroupBuyStatus nextStatus = nextCount >= targetSize ? GroupBuyStatus.SUCCEEDED : GroupBuyStatus.OPEN;
        return new GroupBuyTeam(id, activityId, skuId, leaderUserId, targetSize, nextCount, nextStatus, expiresAt);
    }

    public GroupBuyTeam cancelIfExpired(LocalDateTime now) {
        if (GroupBuyStatus.OPEN.equals(status) && !now.isBefore(expiresAt)) {
            return new GroupBuyTeam(
                    id, activityId, skuId, leaderUserId, targetSize, joinedCount, GroupBuyStatus.CANCELLED, expiresAt);
        }
        return this;
    }
}
