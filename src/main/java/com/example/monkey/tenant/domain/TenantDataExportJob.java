package com.example.monkey.tenant.domain;

import java.time.LocalDateTime;

public record TenantDataExportJob(
        Long id,
        Long tenantId,
        String exportType,
        TenantExportStatus status,
        String encryptedArchivePath,
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
        status = status == null ? TenantExportStatus.REQUESTED : status;
        requestedAt = requestedAt == null ? LocalDateTime.now() : requestedAt;
    }

    public TenantDataExportJob complete(String archivePath, String traceId) {
        return new TenantDataExportJob(
                id,
                tenantId,
                exportType,
                TenantExportStatus.COMPLETED,
                archivePath,
                requestedBy,
                requestedAt,
                LocalDateTime.now(),
                traceId,
                null,
                version + 1);
    }
}
