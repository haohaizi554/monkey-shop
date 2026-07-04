package com.example.monkey.membership.application.dto;

import java.math.BigDecimal;

public record PointsWalletDto(
        Long userId, long balance, long totalEarned, long totalSpent, BigDecimal moneyEquivalent, long version) {}
