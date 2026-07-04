package com.example.monkey.membership.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.monkey.shared.domain.exception.BusinessException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class MembershipDomainTest {

    @Test
    void levelIsDerivedFromGrowthThresholds() {
        assertThat(MembershipLevel.fromGrowth(0)).isEqualTo(MembershipLevel.BASIC);
        assertThat(MembershipLevel.fromGrowth(1000)).isEqualTo(MembershipLevel.SILVER);
        assertThat(MembershipLevel.fromGrowth(5000)).isEqualTo(MembershipLevel.GOLD);
        assertThat(MembershipLevel.fromGrowth(20000)).isEqualTo(MembershipLevel.DIAMOND);
    }

    @Test
    void walletRejectsNegativeBalanceAndTracksEarnedSpent() {
        PointsWallet wallet = new PointsWallet(1L, 7L, 100, 100, 0, 0, now(), now());

        PointsWallet earned = wallet.apply(50, now());
        PointsWallet spent = earned.apply(-80, now());

        assertThat(earned.balance()).isEqualTo(150);
        assertThat(earned.totalEarned()).isEqualTo(150);
        assertThat(spent.balance()).isEqualTo(70);
        assertThat(spent.totalSpent()).isEqualTo(80);
        assertThatThrownBy(() -> wallet.apply(-101, now())).isInstanceOf(BusinessException.class);
    }

    @Test
    void collectionDetectsTargetPriceDropOnce() {
        MemberCollection collection = new MemberCollection(
                1L, 2L, 3L, "Phone", null, BigDecimal.valueOf(199), BigDecimal.valueOf(99), false, 0, now(), now());

        assertThat(collection.priceDropped(BigDecimal.valueOf(120))).isFalse();
        assertThat(collection.priceDropped(BigDecimal.valueOf(99))).isTrue();
        assertThat(collection.refreshPrice(BigDecimal.valueOf(99), true, now()).priceDropped(BigDecimal.valueOf(89)))
                .isFalse();
    }

    private static LocalDateTime now() {
        return LocalDateTime.of(2026, 7, 4, 10, 0);
    }
}
