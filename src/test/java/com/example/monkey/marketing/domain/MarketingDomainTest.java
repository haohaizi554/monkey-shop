package com.example.monkey.marketing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class MarketingDomainTest {

    @Test
    void couponComputesThresholdDiscountWithinOrderAmount() {
        CouponDefinition coupon = new CouponDefinition(
                1L,
                "PLATFORM-20",
                "Platform coupon",
                CouponType.THRESHOLD,
                new BigDecimal("100.00"),
                new BigDecimal("20.00"),
                BigDecimal.ZERO,
                null,
                null,
                "PLATFORM",
                100,
                0,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(1));

        assertThat(coupon.discountFor(new BigDecimal("128.00"))).isEqualByComparingTo("20.00");
        assertThat(coupon.discountFor(new BigDecimal("88.00"))).isEqualByComparingTo("0.00");
    }

    @Test
    void groupBuyTeamCancelsOnlyWhenOpenAndExpired() {
        LocalDateTime now = LocalDateTime.now();
        GroupBuyTeam team = new GroupBuyTeam(1L, 2L, 3L, 4L, 2, 1, GroupBuyStatus.OPEN, now.minusMinutes(1));

        assertThat(team.cancelIfExpired(now).status()).isEqualTo(GroupBuyStatus.CANCELLED);
        assertThat(team.join().status()).isEqualTo(GroupBuyStatus.SUCCEEDED);
    }
}
