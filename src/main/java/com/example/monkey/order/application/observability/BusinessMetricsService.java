package com.example.monkey.order.application.observability;

import com.example.monkey.order.domain.PendingOrderCounter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class BusinessMetricsService {

    private final Timer orderCreateTimer;
    private final Counter orderCreatedCounter;
    private final Counter stockDeductFailureCounter;

    public BusinessMetricsService(MeterRegistry meterRegistry, PendingOrderCounter pendingOrderCounter) {
        this.orderCreateTimer = Timer.builder("order.create")
                .description("Order creation latency")
                .register(meterRegistry);
        this.orderCreatedCounter = Counter.builder("order.created")
                .description("Successfully created orders")
                .register(meterRegistry);
        this.stockDeductFailureCounter = Counter.builder("stock.deduct.fail")
                .description("Failed stock deduction attempts")
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
}
