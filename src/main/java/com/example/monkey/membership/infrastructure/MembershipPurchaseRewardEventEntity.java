package com.example.monkey.membership.infrastructure;

import com.example.monkey.membership.domain.PurchaseRewardEventType;
import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "membership_purchase_reward_event")
public class MembershipPurchaseRewardEventEntity extends TenantScopedJpaEntity {

    @Id
    private Long id;

    @Column(nullable = false)
    private Long rewardFactId;

    @Column(nullable = false)
    private Long paymentId;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PurchaseRewardEventType type;

    @Column(nullable = false, length = 256)
    private String eventKey;

    @Column(nullable = false, columnDefinition = "CHAR(64)")
    private String fingerprint;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal refundAmount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal cumulativeRefundedAmount;

    @Column(nullable = false)
    private long targetReversedPoints;

    @Column(nullable = false)
    private long appliedPoints;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRewardFactId() {
        return rewardFactId;
    }

    public void setRewardFactId(Long rewardFactId) {
        this.rewardFactId = rewardFactId;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(Long paymentId) {
        this.paymentId = paymentId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public PurchaseRewardEventType getType() {
        return type;
    }

    public void setType(PurchaseRewardEventType type) {
        this.type = type;
    }

    public String getEventKey() {
        return eventKey;
    }

    public void setEventKey(String eventKey) {
        this.eventKey = eventKey;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(BigDecimal refundAmount) {
        this.refundAmount = refundAmount;
    }

    public BigDecimal getCumulativeRefundedAmount() {
        return cumulativeRefundedAmount;
    }

    public void setCumulativeRefundedAmount(BigDecimal cumulativeRefundedAmount) {
        this.cumulativeRefundedAmount = cumulativeRefundedAmount;
    }

    public long getTargetReversedPoints() {
        return targetReversedPoints;
    }

    public void setTargetReversedPoints(long targetReversedPoints) {
        this.targetReversedPoints = targetReversedPoints;
    }

    public long getAppliedPoints() {
        return appliedPoints;
    }

    public void setAppliedPoints(long appliedPoints) {
        this.appliedPoints = appliedPoints;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
