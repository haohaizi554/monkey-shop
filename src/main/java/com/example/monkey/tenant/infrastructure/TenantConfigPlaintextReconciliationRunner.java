package com.example.monkey.tenant.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Runs the tenant-config rewrite only during an explicitly approved rollout window. */
@Component
@ConditionalOnProperty(prefix = "app.tenant.config-backfill", name = "enabled", havingValue = "true")
public class TenantConfigPlaintextReconciliationRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantConfigPlaintextReconciliationRunner.class);

    private final TenantConfigPlaintextReconciliationService reconciliationService;

    public TenantConfigPlaintextReconciliationRunner(TenantConfigPlaintextReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        TenantConfigPlaintextReconciliationService.ReconciliationReport report =
                reconciliationService.reconcileLegacyPlaintext();
        LOGGER.info(
                "Tenant config plaintext reconciliation completed: configs={}, history={}, total={}",
                report.configs(),
                report.history(),
                report.total());
    }
}
