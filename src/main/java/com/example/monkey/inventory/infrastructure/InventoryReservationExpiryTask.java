package com.example.monkey.inventory.infrastructure;

import com.example.monkey.inventory.application.InventoryApplicationService;
import com.example.monkey.shared.application.tenant.ActiveTenantIterator;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InventoryReservationExpiryTask {

    private final InventoryApplicationService inventoryApplicationService;
    private final ActiveTenantIterator activeTenantIterator;

    public InventoryReservationExpiryTask(
            InventoryApplicationService inventoryApplicationService, ActiveTenantIterator activeTenantIterator) {
        this.inventoryApplicationService = inventoryApplicationService;
        this.activeTenantIterator = activeTenantIterator;
    }

    @Scheduled(fixedDelayString = "${app.inventory.release-expired-delay:PT1M}")
    @SchedulerLock(
            name = "inventory-release-expired-reservations",
            lockAtMostFor = "${app.inventory.release-lock-at-most-for:PT10M}")
    public void releaseExpiredReservations() {
        activeTenantIterator.forEachRetainedTenant(
                tenantId -> inventoryApplicationService.releaseExpiredReservations());
    }
}
