package com.example.monkey.service;

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

        assertThat(value).isEqualTo("created");
        assertThat(meterRegistry.find("order.create").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find("order.created").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.find("stock.deduct.fail").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.find("order.pending").gauge().value()).isEqualTo(7);
    }
}
