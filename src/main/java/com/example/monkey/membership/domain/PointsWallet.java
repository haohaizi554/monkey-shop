package com.example.monkey.membership.domain;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.time.LocalDateTime;

public record PointsWallet(
        Long id,
        Long userId,
        long balance,
        long totalEarned,
        long totalSpent,
        long version,
        LocalDateTime createTime,
        LocalDateTime updateTime) {

    public PointsWallet {
        if (balance < 0 || totalEarned < 0 || totalSpent < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Points wallet values must be non-negative");
        }
    }

    public PointsWallet apply(long points, LocalDateTime now) {
        long nextBalance = balance + points;
        if (nextBalance < 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Insufficient points balance");
        }
        long nextEarned = points > 0 ? totalEarned + points : totalEarned;
        long nextSpent = points < 0 ? totalSpent + Math.abs(points) : totalSpent;
        return new PointsWallet(id, userId, nextBalance, nextEarned, nextSpent, version, createTime, now);
    }
}
