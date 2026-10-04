package com.example.monkey.order.infrastructure;

import com.example.monkey.order.application.OrderService;
import com.example.monkey.shared.application.tenant.ActiveTenantIterator;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderAutoReceiveTask {

    private final OrderService orderService;
    private final ActiveTenantIterator activeTenantIterator;

    public OrderAutoReceiveTask(OrderService orderService, ActiveTenantIterator activeTenantIterator) {
        this.orderService = orderService;
        this.activeTenantIterator = activeTenantIterator;
    }

    @Scheduled(fixedDelayString = "${app.order.auto-receive-delay:PT5M}")
    @SchedulerLock(
            name = "order-auto-receive-shipments",
            lockAtMostFor = "${app.order.auto-receive-lock-at-most-for:PT10M}")
    public void autoReceiveOverdueShipments() {
        activeTenantIterator.forEachRetainedTenant(tenantId -> orderService.autoReceiveOverdueShipments());
    }
}
