package com.example.monkey.search.infrastructure;

import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_search_profile")
public class UserSearchProfileEntity extends TenantScopedJpaEntity {

    @Id
    private Long userId;

    @Column(length = 2048)
    private String encryptedInterestProfile;

    @Column(columnDefinition = "CHAR(64)")
    private String interestProfileHmac;

    @Column(columnDefinition = "json")
    private String tagVectorJson;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    public UserSearchProfileEntity() {}

    public UserSearchProfileEntity(Long userId) {
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getEncryptedInterestProfile() {
        return encryptedInterestProfile;
    }

    public void setEncryptedInterestProfile(String encryptedInterestProfile) {
        this.encryptedInterestProfile = encryptedInterestProfile;
    }

    public String getInterestProfileHmac() {
        return interestProfileHmac;
    }

    public void setInterestProfileHmac(String interestProfileHmac) {
        this.interestProfileHmac = interestProfileHmac;
    }

    public String getTagVectorJson() {
        return tagVectorJson;
    }

    public void setTagVectorJson(String tagVectorJson) {
        this.tagVectorJson = tagVectorJson;
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
}
