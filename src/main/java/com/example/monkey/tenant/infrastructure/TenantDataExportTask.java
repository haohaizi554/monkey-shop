package com.example.monkey.tenant.infrastructure;

import com.example.monkey.tenant.application.TenantApplicationService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.tenant.export-scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class TenantDataExportTask {

    private final TenantApplicationService tenantApplicationService;

    public TenantDataExportTask(TenantApplicationService tenantApplicationService) {
        this.tenantApplicationService = tenantApplicationService;
    }

    @Scheduled(cron = "${app.tenant.export-scheduler.cron:0 */5 * * * *}")
    @SchedulerLock(name = "tenant-data-export", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void completePendingExports() {
        tenantApplicationService.completePendingExports();
    }
}
