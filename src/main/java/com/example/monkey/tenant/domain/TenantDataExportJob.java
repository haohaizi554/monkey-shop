package com.example.monkey.tenant.domain;

import java.time.LocalDateTime;

public record TenantDataExportJob(
        Long id,
        Long tenantId,
        String exportType,
        TenantExportStatus status,
        String providerJobId,
        String artifactUri,
        Long requestedBy,
        LocalDateTime requestedAt,
        LocalDateTime completedAt,
        String auditTraceId,
        String errorMessage,
        long version) {

    public TenantDataExportJob {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("tenant id is required");
        }
        exportType = exportType == null || exportType.isBlank() ? "FULL" : exportType.trim();
        status = status == null ? TenantExportStatus.QUEUED : status;
        providerJobId = normalize(providerJobId);
        artifactUri = normalize(artifactUri);
        errorMessage = normalize(errorMessage);
        requestedAt = requestedAt == null ? LocalDateTime.now() : requestedAt;
        if ((status == TenantExportStatus.RUNNING || status == TenantExportStatus.SUCCEEDED) && providerJobId == null) {
            throw new IllegalArgumentException("provider job id is required");
        }
        if (status == TenantExportStatus.SUCCEEDED && artifactUri == null) {
            throw new IllegalArgumentException("provider artifact uri is required");
        }
        if (status != TenantExportStatus.SUCCEEDED && artifactUri != null) {
            throw new IllegalArgumentException("only succeeded exports may contain an artifact uri");
        }
    }

    public TenantDataExportJob apply(TenantExportProvider.ExportResult result, LocalDateTime now) {
        if (providerJobId != null && result.providerJobId() != null && !providerJobId.equals(result.providerJobId())) {
            throw new IllegalArgumentException("provider job identity cannot change");
        }
        String nextProviderJobId = result.providerJobId() == null ? providerJobId : result.providerJobId();
        LocalDateTime terminalAt = result.status() == TenantExportStatus.SUCCEEDED
                        || result.status() == TenantExportStatus.FAILED
                        || result.status() == TenantExportStatus.UNAVAILABLE
                ? now
                : null;
        return new TenantDataExportJob(
                id,
                tenantId,
                exportType,
                result.status(),
                nextProviderJobId,
                result.artifactUri(),
                requestedBy,
                requestedAt,
                terminalAt,
                auditTraceId,
                result.errorMessage(),
                version + 1);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
