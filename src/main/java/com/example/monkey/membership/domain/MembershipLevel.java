package com.example.monkey.membership.domain;

import java.math.BigDecimal;
import java.util.List;

public enum MembershipLevel {
    BASIC(0, BigDecimal.valueOf(1.00), 1, List.of("standard-service")),
    SILVER(1000, BigDecimal.valueOf(0.98), 2, List.of("standard-service", "monthly-coupon")),
    GOLD(5000, BigDecimal.valueOf(0.95), 3, List.of("priority-service", "monthly-coupon", "freight-coupon")),
    DIAMOND(20000, BigDecimal.valueOf(0.90), 5, List.of("priority-service", "vip-coupon", "freight-coupon"));

    private final long minGrowth;
    private final BigDecimal discountRate;
    private final int pointsMultiplier;
    private final List<String> benefits;

    MembershipLevel(long minGrowth, BigDecimal discountRate, int pointsMultiplier, List<String> benefits) {
        this.minGrowth = minGrowth;
        this.discountRate = discountRate;
        this.pointsMultiplier = pointsMultiplier;
        this.benefits = List.copyOf(benefits);
    }

    public long minGrowth() {
        return minGrowth;
    }

    public BigDecimal discountRate() {
        return discountRate;
    }

    public int pointsMultiplier() {
        return pointsMultiplier;
    }

    public List<String> benefits() {
        return benefits;
    }

    public static MembershipLevel fromGrowth(long growthValue) {
        MembershipLevel matched = BASIC;
        for (MembershipLevel level : values()) {
            if (growthValue >= level.minGrowth) {
                matched = level;
            }
        }
        return matched;
    }
}
