package com.example.monkey.order.application.observability;

import com.example.monkey.order.domain.PendingOrderCounter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class BusinessMetricsService {

    private final Timer orderCreateTimer;
    private final Counter orderCreatedCounter;
    private final Counter stockDeductFailureCounter;
    private final Counter searchConversionCounter;
    private final Counter riskHighScoreCounter;
    private final Counter riskBlockedCounter;
    private final Counter riskPriceAnomalyCounter;
    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicLong> funnelSnapshots = new ConcurrentHashMap<>();

    public BusinessMetricsService(MeterRegistry meterRegistry, PendingOrderCounter pendingOrderCounter) {
        this.meterRegistry = meterRegistry;
        this.orderCreateTimer = Timer.builder("order.create")
                .description("Order creation latency")
                .register(meterRegistry);
        this.orderCreatedCounter = Counter.builder("order.created")
                .description("Successfully created orders")
                .register(meterRegistry);
        this.stockDeductFailureCounter = Counter.builder("stock.deduct.fail")
                .description("Failed stock deduction attempts")
                .register(meterRegistry);
        this.searchConversionCounter = Counter.builder("search.conversion")
                .description("Search result conversion events")
                .register(meterRegistry);
        this.riskHighScoreCounter = Counter.builder("risk.high_score")
                .description("Risk decisions with score at or above 80")
                .register(meterRegistry);
        this.riskBlockedCounter = Counter.builder("risk.blocked")
                .description("Risk decisions that revoked user tokens")
                .register(meterRegistry);
        this.riskPriceAnomalyCounter = Counter.builder("risk.price_anomaly")
                .description("Price anomaly decisions that auto-unlisted a product")
                .register(meterRegistry);
        Gauge.builder("order.pending", pendingOrderCounter, counter -> counter.countPendingOrders())
                .description("Orders paid but not shipped")
                .strongReference(true)
                .register(meterRegistry);
    }

    public <T> T recordOrderCreate(Supplier<T> operation) {
        Timer.Sample sample = Timer.start();
        try {
            return operation.get();
        } finally {
            sample.stop(orderCreateTimer);
        }
    }

    public void recordOrderCreated() {
        orderCreatedCounter.increment();
    }

    public void recordStockDeductFailure() {
        stockDeductFailureCounter.increment();
    }

    public void recordSearchConversion() {
        searchConversionCounter.increment();
    }

    public void recordRiskDecision(int score, boolean priceAnomaly, boolean blocked) {
        if (score >= 80) {
            riskHighScoreCounter.increment();
        }
        if (priceAnomaly) {
            riskPriceAnomalyCounter.increment();
        }
        if (blocked) {
            riskBlockedCounter.increment();
        }
    }

    public void recordRiskBlocked() {
        riskBlockedCounter.increment();
    }

    public void recordRiskPriceAnomaly() {
        riskPriceAnomalyCounter.increment();
    }

    public void recordTrackingEvent(String eventType) {
        Counter.builder("tracking.event")
                .description("Tracking events accepted by the data platform")
                .tag("type", eventType == null ? "UNKNOWN" : eventType)
                .register(meterRegistry)
                .increment();
    }

    public void recordFunnelSnapshot(String step, long count) {
        String normalizedStep = step == null ? "UNKNOWN" : step;
        funnelSnapshots
                .computeIfAbsent(normalizedStep, key -> {
                    AtomicLong gaugeValue = new AtomicLong();
                    Gauge.builder("tracking.funnel", gaugeValue, AtomicLong::get)
                            .description("Latest tracking funnel count snapshot")
                            .tag("step", key)
                            .strongReference(true)
                            .register(meterRegistry);
                    return gaugeValue;
                })
                .set(count);
    }
}
