package com.example.monkey.risk.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RiskPolicyTest {

    @Test
    void priceAnomalyRequiresMoreThanFiftyPercentMovement() {
        assertThat(RiskPolicy.priceAnomaly(new BigDecimal("100.00"), new BigDecimal("150.00")))
                .isFalse();
        assertThat(RiskPolicy.priceAnomaly(new BigDecimal("100.00"), new BigDecimal("151.00")))
                .isTrue();
        assertThat(RiskPolicy.priceAnomaly(new BigDecimal("100.00"), new BigDecimal("49.00")))
                .isTrue();
    }

    @Test
    void seckillMultiAccountSameDeviceBlocksWhenScoreIsHigh() {
        RiskAssessment assessment =
                RiskPolicy.assess(new RiskPolicy.RiskPolicyInput(3, 3, 3, false, null, null, false));

        assertThat(assessment.decision()).isEqualTo(RiskDecision.BLOCK);
        assertThat(assessment.score()).isEqualTo(90);
        assertThat(assessment.signals())
                .extracting(RiskSignal::type)
                .contains(RiskSignalType.DEVICE_MULTI_ACCOUNT, RiskSignalType.SECKILL_SCALPER);
    }

    @Test
    void highRiskScoreRequiresTotpBeforeRateLimitDecision() {
        RiskAssessment withoutTotp =
                RiskPolicy.assess(new RiskPolicy.RiskPolicyInput(3, 3, 0, true, null, null, false));
        RiskAssessment withTotp = RiskPolicy.assess(new RiskPolicy.RiskPolicyInput(3, 3, 0, true, null, null, true));

        assertThat(withoutTotp.score()).isEqualTo(89);
        assertThat(withoutTotp.decision()).isEqualTo(RiskDecision.TOTP_REQUIRED);
        assertThat(withTotp.decision()).isEqualTo(RiskDecision.RATE_LIMIT);
    }

    @Test
    void priceAnomalyCreatesManualReviewSignal() {
        RiskAssessment assessment = RiskPolicy.assess(new RiskPolicy.RiskPolicyInput(
                1, 1, 0, false, new BigDecimal("100.00"), new BigDecimal("170.00"), false));

        assertThat(assessment.score()).isEqualTo(65);
        assertThat(assessment.decision()).isEqualTo(RiskDecision.REVIEW);
        assertThat(assessment.signals()).extracting(RiskSignal::type).contains(RiskSignalType.PRICE_ANOMALY);
    }
}
