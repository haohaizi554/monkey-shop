package com.example.monkey.membership.infrastructure;

import com.example.monkey.membership.domain.MembershipLevel;
import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

@Entity
@Table(name = "membership_profile")
public class MembershipProfileEntity extends TenantScopedJpaEntity {

    @Id
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MembershipLevel level;

    @Column(nullable = false)
    private long growthValue;

    @Column(length = 1024)
    private String realNameEncrypted;

    @Column(columnDefinition = "CHAR(64)")
    private String realNameHmac;

    @Column(length = 1024)
    private String idCardEncrypted;

    @Column(columnDefinition = "CHAR(64)")
    private String idCardHmac;

    private LocalDateTime verifiedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public MembershipLevel getLevel() {
        return level;
    }

    public void setLevel(MembershipLevel level) {
        this.level = level;
    }

    public long getGrowthValue() {
        return growthValue;
    }

    public void setGrowthValue(long growthValue) {
        this.growthValue = growthValue;
    }

    public String getRealNameEncrypted() {
        return realNameEncrypted;
    }

    public void setRealNameEncrypted(String realNameEncrypted) {
        this.realNameEncrypted = realNameEncrypted;
    }

    public String getRealNameHmac() {
        return realNameHmac;
    }

    public void setRealNameHmac(String realNameHmac) {
        this.realNameHmac = realNameHmac;
    }

    public String getIdCardEncrypted() {
        return idCardEncrypted;
    }

    public void setIdCardEncrypted(String idCardEncrypted) {
        this.idCardEncrypted = idCardEncrypted;
    }

    public String getIdCardHmac() {
        return idCardHmac;
    }

    public void setIdCardHmac(String idCardHmac) {
        this.idCardHmac = idCardHmac;
    }

    public LocalDateTime getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(LocalDateTime verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
