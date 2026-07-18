package com.example.monkey.tenant.domain;

public interface TenantExportProvider {

    /**
     * Submissions must be idempotent for {@link ExportRequest#jobId()} so a persisted local
     * intent can safely recover an uncertain provider call.
     */
    ExportResult submit(ExportRequest request);

    ExportResult refresh(TenantDataExportJob job);

    record ExportRequest(Long jobId, Long tenantId, String exportType, Long requestedBy, String auditTraceId) {

        public ExportRequest {
            if (jobId == null || jobId <= 0 || tenantId == null || tenantId <= 0) {
                throw new IllegalArgumentException("tenant export identity is required");
            }
            if (requestedBy == null || requestedBy <= 0) {
                throw new IllegalArgumentException("tenant export requester is required");
            }
            exportType = requireText(exportType, "tenant export type is required");
            auditTraceId = requireText(auditTraceId, "tenant export trace is required");
        }
    }

    record ExportResult(TenantExportStatus status, String providerJobId, String artifactUri, String errorMessage) {

        public ExportResult {
            if (status == null) {
                throw new IllegalArgumentException("tenant export status is required");
            }
            providerJobId = normalize(providerJobId);
            artifactUri = normalize(artifactUri);
            errorMessage = normalize(errorMessage);
            if ((status == TenantExportStatus.QUEUED
                            || status == TenantExportStatus.RUNNING
                            || status == TenantExportStatus.SUCCEEDED)
                    && providerJobId == null) {
                throw new IllegalArgumentException("provider job id is required");
            }
            if (status == TenantExportStatus.SUCCEEDED && artifactUri == null) {
                throw new IllegalArgumentException("provider artifact uri is required");
            }
            if (status != TenantExportStatus.SUCCEEDED && artifactUri != null) {
                throw new IllegalArgumentException("only succeeded exports may contain an artifact uri");
            }
            if ((status == TenantExportStatus.FAILED || status == TenantExportStatus.UNAVAILABLE)
                    && errorMessage == null) {
                throw new IllegalArgumentException("terminal export error is required");
            }
        }
    }

    private static String requireText(String value, String message) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
