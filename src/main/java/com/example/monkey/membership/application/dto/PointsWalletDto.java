package com.example.monkey.membership.application.dto;

import java.math.BigDecimal;

public record PointsWalletDto(
        Long userId,
        long balance,
        long totalEarned,
        long totalSpent,
        long pointsDebt,
        BigDecimal moneyEquivalent,
        long version) {

    /** Compatibility constructor for clients written before refund debt was exposed. */
    public PointsWalletDto(
            Long userId, long balance, long totalEarned, long totalSpent, BigDecimal moneyEquivalent, long version) {
        this(userId, balance, totalEarned, totalSpent, 0, moneyEquivalent, version);
    }
}
