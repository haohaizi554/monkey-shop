package com.example.monkey.tenant.infrastructure;

import com.example.monkey.shared.domain.tenant.TenantScoped;
import com.example.monkey.shared.infrastructure.tenant.TenantScopedEntityListener;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "tenant_config_history")
@EntityListeners(TenantScopedEntityListener.class)
public class TenantConfigHistoryEntity implements TenantScoped {

    @Id
    private Long id;

    private Long tenantId;
    private Long configId;

    @Column(columnDefinition = "json")
    private String oldSettingsJson;

    @Column(nullable = false, columnDefinition = "json")
    private String newSettingsJson;

    private Long operatorUserId;
    private LocalDateTime changedAt;

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

    public Long getConfigId() {
        return configId;
    }

    public void setConfigId(Long configId) {
        this.configId = configId;
    }

    public String getOldSettingsJson() {
        return oldSettingsJson;
    }

    public void setOldSettingsJson(String oldSettingsJson) {
        this.oldSettingsJson = oldSettingsJson;
    }

    public String getNewSettingsJson() {
        return newSettingsJson;
    }

    public void setNewSettingsJson(String newSettingsJson) {
        this.newSettingsJson = newSettingsJson;
    }

    public Long getOperatorUserId() {
        return operatorUserId;
    }

    public void setOperatorUserId(Long operatorUserId) {
        this.operatorUserId = operatorUserId;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(LocalDateTime changedAt) {
        this.changedAt = changedAt;
    }
}
