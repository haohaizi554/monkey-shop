package com.example.monkey.order.application.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BusinessMetricsServiceTest {

    @Test
    void registersOrderCountersTimerAndPendingGauge() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AtomicLong pendingOrders = new AtomicLong(7L);
        BusinessMetricsService metricsService = new BusinessMetricsService(meterRegistry, pendingOrders::get);

        String value = metricsService.recordOrderCreate(() -> "created");
        metricsService.recordOrderCreated();
        metricsService.recordStockDeductFailure();
        metricsService.recordSearchConversion();
        metricsService.recordRiskDecision(88, true, true);
        metricsService.recordTrackingEvent("PAYMENT_SUCCESS");
        metricsService.recordFunnelSnapshot("PAYMENT_SUCCESS", 4L);
        metricsService.recordFunnelSnapshot("PAYMENT_SUCCESS", 6L);

        assertThat(value).isEqualTo("created");
        assertThat(timerCount(meterRegistry, "order.create")).isEqualTo(1);
        assertThat(counterCount(meterRegistry, "order.created")).isEqualTo(1);
        assertThat(counterCount(meterRegistry, "stock.deduct.fail")).isEqualTo(1);
        assertThat(counterCount(meterRegistry, "search.conversion")).isEqualTo(1);
        assertThat(counterCount(meterRegistry, "risk.high_score")).isEqualTo(1);
        assertThat(counterCount(meterRegistry, "risk.price_anomaly")).isEqualTo(1);
        assertThat(counterCount(meterRegistry, "risk.blocked")).isEqualTo(1);
        assertThat(counterCount(meterRegistry, "tracking.event", "type", "PAYMENT_SUCCESS"))
                .isEqualTo(1);
        assertThat(gaugeValue(meterRegistry, "tracking.funnel", "step", "PAYMENT_SUCCESS"))
                .isEqualTo(6);
        assertThat(gaugeValue(meterRegistry, "order.pending")).isEqualTo(7);
    }

    private static long timerCount(SimpleMeterRegistry meterRegistry, String name) {
        Timer timer = meterRegistry.find(name).timer();
        assertThat(timer).isNotNull();
        return timer.count();
    }

    private static double counterCount(SimpleMeterRegistry meterRegistry, String name) {
        Counter counter = meterRegistry.find(name).counter();
        assertThat(counter).isNotNull();
        return counter.count();
    }

    private static double counterCount(SimpleMeterRegistry meterRegistry, String name, String tagKey, String tagValue) {
        Counter counter = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        assertThat(counter).isNotNull();
        return counter.count();
    }

    private static double gaugeValue(SimpleMeterRegistry meterRegistry, String name) {
        Gauge gauge = meterRegistry.find(name).gauge();
        assertThat(gauge).isNotNull();
        return gauge.value();
    }

    private static double gaugeValue(SimpleMeterRegistry meterRegistry, String name, String tagKey, String tagValue) {
        Gauge gauge = meterRegistry.find(name).tag(tagKey, tagValue).gauge();
        assertThat(gauge).isNotNull();
        return gauge.value();
    }
}
