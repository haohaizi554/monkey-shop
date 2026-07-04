package com.example.monkey.tracking.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_profile_tag")
public class UserProfileTagEntity {

    @Id
    private Long userId;

    @Column(length = 2048)
    private String encryptedProfileSummary;

    @Column(length = 64)
    private String profileSummaryHmac;

    @Column(columnDefinition = "json")
    private String behaviorTagsJson;

    @Column(columnDefinition = "json")
    private String interestTagsJson;

    @Column(nullable = false)
    private LocalDateTime lastEventAt;

    @Version
    @Column(nullable = false)
    private Long version;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getEncryptedProfileSummary() {
        return encryptedProfileSummary;
    }

    public void setEncryptedProfileSummary(String encryptedProfileSummary) {
        this.encryptedProfileSummary = encryptedProfileSummary;
    }

    public String getProfileSummaryHmac() {
        return profileSummaryHmac;
    }

    public void setProfileSummaryHmac(String profileSummaryHmac) {
        this.profileSummaryHmac = profileSummaryHmac;
    }

    public String getBehaviorTagsJson() {
        return behaviorTagsJson;
    }

    public void setBehaviorTagsJson(String behaviorTagsJson) {
        this.behaviorTagsJson = behaviorTagsJson;
    }

    public String getInterestTagsJson() {
        return interestTagsJson;
    }

    public void setInterestTagsJson(String interestTagsJson) {
        this.interestTagsJson = interestTagsJson;
    }

    public LocalDateTime getLastEventAt() {
        return lastEventAt;
    }

    public void setLastEventAt(LocalDateTime lastEventAt) {
        this.lastEventAt = lastEventAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
