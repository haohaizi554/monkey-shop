package com.example.monkey.membership.infrastructure;

import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "membership_purchase_reward")
public class MembershipPurchaseRewardEntity extends TenantScopedJpaEntity {

    @Id
    private Long id;

    @Column(nullable = false)
    private Long paymentId;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal originalPaidAmount;

    @Column(length = 128)
    private String providerTradeNo;

    @Column(nullable = false)
    private int pointsMultiplier;

    @Column(nullable = false)
    private long awardedPoints;

    @Column(nullable = false)
    private long reversedPoints;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal cumulativeRefundedAmount;

    @Column(nullable = false, length = 256)
    private String awardEventKey;

    @Column(nullable = false, columnDefinition = "CHAR(64)")
    private String awardFingerprint;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public BigDecimal getOriginalPaidAmount() {
        return originalPaidAmount;
    }

    public void setOriginalPaidAmount(BigDecimal originalPaidAmount) {
        this.originalPaidAmount = originalPaidAmount;
    }

    public String getProviderTradeNo() {
        return providerTradeNo;
    }

    public void setProviderTradeNo(String providerTradeNo) {
        this.providerTradeNo = providerTradeNo;
    }

    public int getPointsMultiplier() {
        return pointsMultiplier;
    }

    public void setPointsMultiplier(int pointsMultiplier) {
        this.pointsMultiplier = pointsMultiplier;
    }

    public long getAwardedPoints() {
        return awardedPoints;
    }

    public void setAwardedPoints(long awardedPoints) {
        this.awardedPoints = awardedPoints;
    }

    public long getReversedPoints() {
        return reversedPoints;
    }

    public void setReversedPoints(long reversedPoints) {
        this.reversedPoints = reversedPoints;
    }

    public BigDecimal getCumulativeRefundedAmount() {
        return cumulativeRefundedAmount;
    }

    public void setCumulativeRefundedAmount(BigDecimal cumulativeRefundedAmount) {
        this.cumulativeRefundedAmount = cumulativeRefundedAmount;
    }

    public String getAwardEventKey() {
        return awardEventKey;
    }

    public void setAwardEventKey(String awardEventKey) {
        this.awardEventKey = awardEventKey;
    }

    public String getAwardFingerprint() {
        return awardFingerprint;
    }

    public void setAwardFingerprint(String awardFingerprint) {
        this.awardFingerprint = awardFingerprint;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
