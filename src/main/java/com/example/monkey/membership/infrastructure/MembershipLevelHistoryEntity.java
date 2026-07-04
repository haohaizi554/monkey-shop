package com.example.monkey.membership.infrastructure;

import com.example.monkey.membership.domain.MembershipLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "membership_level_history")
public class MembershipLevelHistoryEntity {

    @Id
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MembershipLevel fromLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MembershipLevel toLevel;

    @Column(nullable = false, length = 128)
    private String reason;

    private Long operatorUserId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public void setId(Long id) {
        this.id = id;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public void setFromLevel(MembershipLevel fromLevel) {
        this.fromLevel = fromLevel;
    }

    public void setToLevel(MembershipLevel toLevel) {
        this.toLevel = toLevel;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public void setOperatorUserId(Long operatorUserId) {
        this.operatorUserId = operatorUserId;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
