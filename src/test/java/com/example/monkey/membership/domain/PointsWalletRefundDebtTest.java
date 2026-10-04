package com.example.monkey.membership.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PointsWalletRefundDebtTest {

    private static final LocalDateTime NOW = LocalDateTime.parse("2026-08-28T10:00:00");

    @Test
    void refundReversalConsumesAvailablePointsAndFutureEarningsRepayDebtBeforeBecomingSpendable() {
        PointsWallet wallet = new PointsWallet(1L, 7L, 50, 100, 50, 0, 0, NOW, NOW);

        PointsWallet afterRefund = wallet.applyRefundReversal(80, NOW);
        PointsWallet afterFutureEarn = afterRefund.apply(50, NOW);

        assertThat(afterRefund.balance()).isZero();
        assertThat(afterRefund.pointsDebt()).isEqualTo(30);
        assertThat(afterFutureEarn.balance()).isEqualTo(20);
        assertThat(afterFutureEarn.pointsDebt()).isZero();
        assertThat(afterFutureEarn.totalEarned()).isEqualTo(150);
        assertThat(afterFutureEarn.totalSpent()).isEqualTo(50);
    }
}
