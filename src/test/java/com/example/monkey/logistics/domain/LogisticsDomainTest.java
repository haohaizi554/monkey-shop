package com.example.monkey.logistics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogisticsDomainTest {

    @Test
    void freightCalculatorCombinesWeightItemAndRegionDimensions() {
        FreightQuote quote = new FreightCalculator()
                .quote(
                        LogisticsCarrier.SF,
                        "Zhejiang",
                        new BigDecimal("2.20"),
                        2,
                        List.of(
                                template(FreightChargeMode.WEIGHT, "*", "18.00", "6.00", "0.00", "0.00"),
                                template(FreightChargeMode.ITEM, "*", "0.00", "0.00", "3.00", "0.00"),
                                template(FreightChargeMode.REGION, "Zhejiang", "0.00", "0.00", "0.00", "5.00")));

        assertThat(quote.amount()).isEqualByComparingTo(new BigDecimal("41.00"));
        assertThat(quote.appliedModes())
                .containsExactlyInAnyOrder(FreightChargeMode.WEIGHT, FreightChargeMode.ITEM, FreightChargeMode.REGION);
    }

    @Test
    void transitionPolicyRejectsSkippingPickup() {
        assertThat(LogisticsTransitionPolicy.nextStatus(TrackingStatus.ORDERED, TrackingEvent.PICKUP))
                .contains(TrackingStatus.PICKED_UP);
        assertThat(LogisticsTransitionPolicy.nextStatus(TrackingStatus.ORDERED, TrackingEvent.SIGN))
                .isEmpty();
    }

    @Test
    void freightCalculatorFailsClosedWhenTemplateMissing() {
        assertThatThrownBy(
                        () -> new FreightCalculator().quote(LogisticsCarrier.YTO, "Mars", BigDecimal.ONE, 1, List.of()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private static FreightTemplate template(
            FreightChargeMode mode, String province, String baseFee, String stepFee, String itemFee, String regionFee) {
        return new FreightTemplate(
                1L,
                LogisticsCarrier.SF,
                province,
                mode,
                BigDecimal.ONE,
                new BigDecimal(baseFee),
                BigDecimal.ONE,
                new BigDecimal(stepFee),
                new BigDecimal(itemFee),
                new BigDecimal(regionFee),
                24,
                true);
    }
}
