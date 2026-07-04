package com.example.monkey.tenant.infrastructure;

import com.example.monkey.shared.domain.tenant.TenantScoped;
import com.example.monkey.shared.infrastructure.tenant.TenantScopedEntityListener;
import com.example.monkey.tenant.domain.TenantExportStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "tenant_data_export_job")
@EntityListeners(TenantScopedEntityListener.class)
public class TenantDataExportJobEntity implements TenantScoped {

    @Id
    private Long id;

    private Long tenantId;

    @Column(nullable = false, length = 32)
    private String exportType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TenantExportStatus status;

    private String encryptedArchivePath;
    private Long requestedBy;
    private LocalDateTime requestedAt;
    private LocalDateTime completedAt;
    private String auditTraceId;
    private String errorMessage;
    private Long version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    @Override
    public Long getTenantId() {
        return tenantId;
    }

    @Override
    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getExportType() {
        return exportType;
    }

    public void setExportType(String exportType) {
        this.exportType = exportType;
    }

    public TenantExportStatus getStatus() {
        return status;
    }

    public void setStatus(TenantExportStatus status) {
        this.status = status;
    }

    public String getEncryptedArchivePath() {
        return encryptedArchivePath;
    }

    public void setEncryptedArchivePath(String encryptedArchivePath) {
        this.encryptedArchivePath = encryptedArchivePath;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(Long requestedBy) {
        this.requestedBy = requestedBy;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(LocalDateTime requestedAt) {
        this.requestedAt = requestedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public String getAuditTraceId() {
        return auditTraceId;
    }

    public void setAuditTraceId(String auditTraceId) {
        this.auditTraceId = auditTraceId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
