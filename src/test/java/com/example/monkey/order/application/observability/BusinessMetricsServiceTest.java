package com.example.monkey.order.application.observability;

import static org.assertj.core.api.Assertions.assertThat;

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

        assertThat(value).isEqualTo("created");
        assertThat(meterRegistry.find("order.create").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find("order.created").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.find("stock.deduct.fail").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.find("search.conversion").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.find("risk.high_score").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.find("risk.price_anomaly").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.find("risk.blocked").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.find("order.pending").gauge().value()).isEqualTo(7);
    }
}
