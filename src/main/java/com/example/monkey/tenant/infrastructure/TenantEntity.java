package com.example.monkey.tenant.infrastructure;

import com.example.monkey.tenant.domain.TenantPlan;
import com.example.monkey.tenant.domain.TenantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "tenant")
public class TenantEntity {

    @Id
    private Long id;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TenantStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TenantPlan plan;

    private String contactName;

    @Column(length = 1024)
    private String encryptedContactPhone;

    @Column(columnDefinition = "CHAR(64)")
    private String contactPhoneHmac;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private Long version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public void setStatus(TenantStatus status) {
        this.status = status;
    }

    public TenantPlan getPlan() {
        return plan;
    }

    public void setPlan(TenantPlan plan) {
        this.plan = plan;
    }

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public String getEncryptedContactPhone() {
        return encryptedContactPhone;
    }

    public void setEncryptedContactPhone(String encryptedContactPhone) {
        this.encryptedContactPhone = encryptedContactPhone;
    }

    public String getContactPhoneHmac() {
        return contactPhoneHmac;
    }

    public void setContactPhoneHmac(String contactPhoneHmac) {
        this.contactPhoneHmac = contactPhoneHmac;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
