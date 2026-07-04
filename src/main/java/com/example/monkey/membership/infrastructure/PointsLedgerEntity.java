package com.example.monkey.membership.infrastructure;

import com.example.monkey.membership.domain.PointsLedgerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "membership_points_ledger")
public class PointsLedgerEntity {

    @Id
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PointsLedgerType type;

    @Column(nullable = false)
    private long points;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal moneyEquivalent;

    private Long orderId;

    @Column(length = 160)
    private String referenceKey;

    @Column(nullable = false, length = 160)
    private String idempotencyKey;

    @Column(nullable = false)
    private LocalDateTime createdAt;

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

    public PointsLedgerType getType() {
        return type;
    }

    public void setType(PointsLedgerType type) {
        this.type = type;
    }

    public long getPoints() {
        return points;
    }

    public void setPoints(long points) {
        this.points = points;
    }

    public BigDecimal getMoneyEquivalent() {
        return moneyEquivalent;
    }

    public void setMoneyEquivalent(BigDecimal moneyEquivalent) {
        this.moneyEquivalent = moneyEquivalent;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getReferenceKey() {
        return referenceKey;
    }

    public void setReferenceKey(String referenceKey) {
        this.referenceKey = referenceKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
