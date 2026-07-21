package com.example.monkey.tenant.application.dto;

import com.example.monkey.tenant.domain.TenantExportStatus;
import java.time.LocalDateTime;

public record TenantExportJobDto(
        Long id,
        Long tenantId,
        String exportType,
        TenantExportStatus status,
        boolean artifactAvailable,
        String artifactDownloadUri,
        Long requestedBy,
        LocalDateTime requestedAt,
        LocalDateTime completedAt,
        String auditTraceId,
        long version) {}
