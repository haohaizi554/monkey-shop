package com.example.monkey.tenant.infrastructure;

import com.example.monkey.shared.domain.tenant.TenantScoped;
import com.example.monkey.shared.infrastructure.tenant.TenantScopedEntityListener;
import com.example.monkey.tenant.domain.TenantConfigType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "tenant_config")
@EntityListeners(TenantScopedEntityListener.class)
public class TenantConfigEntity implements TenantScoped {

    @Id
    private Long id;

    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TenantConfigType configType;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(nullable = false, columnDefinition = "json")
    private String settingsJson;

    private boolean enabled;
    private LocalDateTime updatedAt;
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

    public TenantConfigType getConfigType() {
        return configType;
    }

    public void setConfigType(TenantConfigType configType) {
        this.configType = configType;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getSettingsJson() {
        return settingsJson;
    }

    public void setSettingsJson(String settingsJson) {
        this.settingsJson = settingsJson;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
