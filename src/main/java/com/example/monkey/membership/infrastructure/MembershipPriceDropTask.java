package com.example.monkey.membership.infrastructure;

import com.example.monkey.membership.application.MembershipApplicationService;
import com.example.monkey.shared.application.tenant.ActiveTenantIterator;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MembershipPriceDropTask {

    private final MembershipApplicationService membershipApplicationService;
    private final ActiveTenantIterator activeTenantIterator;

    public MembershipPriceDropTask(
            MembershipApplicationService membershipApplicationService, ActiveTenantIterator activeTenantIterator) {
        this.membershipApplicationService = membershipApplicationService;
        this.activeTenantIterator = activeTenantIterator;
    }

    @Scheduled(fixedDelayString = "${app.membership.price-drop-scan-delay:PT5M}")
    @SchedulerLock(
            name = "membership-price-drop-scan",
            lockAtMostFor = "${app.membership.price-drop-lock-at-most-for:PT10M}")
    public void scanPriceDrops() {
        activeTenantIterator.forEachActiveTenant(
                tenantId -> membershipApplicationService.scanPriceDrops().scanned());
    }
}
