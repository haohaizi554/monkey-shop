package com.example.monkey.tenant.infrastructure;

import com.example.monkey.tenant.domain.TenantDataExportJob;
import com.example.monkey.tenant.domain.TenantExportProvider;
import com.example.monkey.tenant.domain.TenantExportStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.tenant.export.provider", havingValue = "unavailable", matchIfMissing = true)
public class UnavailableTenantExportProvider implements TenantExportProvider {

    static final String NOT_CONFIGURED = "tenant export provider is not configured";

    @Override
    public ExportResult submit(ExportRequest request) {
        return new ExportResult(TenantExportStatus.UNAVAILABLE, null, null, NOT_CONFIGURED);
    }

    @Override
    public ExportResult refresh(TenantDataExportJob job) {
        return new ExportResult(TenantExportStatus.UNAVAILABLE, job.providerJobId(), null, NOT_CONFIGURED);
    }
}
