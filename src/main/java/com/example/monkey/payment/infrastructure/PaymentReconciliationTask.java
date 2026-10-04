package com.example.monkey.payment.infrastructure;

import com.example.monkey.payment.application.PaymentApplicationService;
import com.example.monkey.shared.application.tenant.ActiveTenantIterator;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentReconciliationTask {

    private final PaymentApplicationService paymentApplicationService;
    private final ActiveTenantIterator activeTenantIterator;

    public PaymentReconciliationTask(
            PaymentApplicationService paymentApplicationService, ActiveTenantIterator activeTenantIterator) {
        this.paymentApplicationService = paymentApplicationService;
        this.activeTenantIterator = activeTenantIterator;
    }

    @Scheduled(cron = "${app.payment.reconciliation-cron:0 35 3 * * *}")
    @SchedulerLock(
            name = "payment-daily-reconciliation",
            lockAtMostFor = "${app.payment.reconciliation-lock-at-most-for:PT30M}")
    public void reconcileYesterday() {
        activeTenantIterator.forEachRetainedTenant(tenantId -> {
            paymentApplicationService.reconcileYesterday();
            return 1L;
        });
    }
}
