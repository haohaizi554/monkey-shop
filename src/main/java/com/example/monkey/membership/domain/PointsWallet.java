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
        long pointsDebt,
        long version,
        LocalDateTime createTime,
        LocalDateTime updateTime) {

    public PointsWallet {
        if (balance < 0 || totalEarned < 0 || totalSpent < 0 || pointsDebt < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Points wallet values must be non-negative");
        }
    }

    /** Compatibility constructor for callers written before refund debt was persisted. */
    public PointsWallet(
            Long id,
            Long userId,
            long balance,
            long totalEarned,
            long totalSpent,
            long version,
            LocalDateTime createTime,
            LocalDateTime updateTime) {
        this(id, userId, balance, totalEarned, totalSpent, 0, version, createTime, updateTime);
    }

    public PointsWallet apply(long points, LocalDateTime now) {
        if (points == 0) {
            return new PointsWallet(id, userId, balance, totalEarned, totalSpent, pointsDebt, version, createTime, now);
        }
        try {
            if (points > 0) {
                long debtRepaid = Math.min(pointsDebt, points);
                long spendable = points - debtRepaid;
                return new PointsWallet(
                        id,
                        userId,
                        Math.addExact(balance, spendable),
                        Math.addExact(totalEarned, points),
                        totalSpent,
                        pointsDebt - debtRepaid,
                        version,
                        createTime,
                        now);
            }
            long spent = Math.negateExact(points);
            long nextBalance = Math.subtractExact(balance, spent);
            if (nextBalance < 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "Insufficient points balance");
            }
            return new PointsWallet(
                    id,
                    userId,
                    nextBalance,
                    totalEarned,
                    Math.addExact(totalSpent, spent),
                    pointsDebt,
                    version,
                    createTime,
                    now);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Points wallet value is too large");
        }
    }

    /**
     * Reverses points previously awarded for a purchase. Available points are consumed first; any remainder is
     * recorded as a debt which future positive earnings repay before becoming spendable.
     */
    public PointsWallet applyRefundReversal(long points, LocalDateTime now) {
        if (points < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Refund reversal points must be non-negative");
        }
        if (points == 0) {
            return new PointsWallet(id, userId, balance, totalEarned, totalSpent, pointsDebt, version, createTime, now);
        }
        try {
            long consumed = Math.min(balance, points);
            return new PointsWallet(
                    id,
                    userId,
                    balance - consumed,
                    totalEarned,
                    totalSpent,
                    Math.addExact(pointsDebt, points - consumed),
                    version,
                    createTime,
                    now);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Points debt is too large");
        }
    }
}
