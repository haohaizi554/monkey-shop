package com.example.monkey.logistics.infrastructure;

import com.example.monkey.logistics.domain.ShipmentClaimStatus;
import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/** Durable gate written before an external shipment-create call. */
@Entity
@Table(
        name = "logistics_shipment_claim",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_logistics_shipment_claim_order",
                    columnNames = {"tenant_id", "order_id"}),
            @UniqueConstraint(
                    name = "uk_logistics_shipment_claim_idempotency",
                    columnNames = {"tenant_id", "owner_user_id", "idempotency_key"}),
            @UniqueConstraint(
                    name = "uk_logistics_shipment_claim_provider_token",
                    columnNames = {"tenant_id", "provider_token"})
        })
public class LogisticsShipmentClaimEntity extends TenantScopedJpaEntity {

    @Id
    @Column(name = "shipment_id")
    private Long shipmentId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "provider_token", nullable = false, length = 64)
    private String providerToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ShipmentClaimStatus status;

    @Column(name = "claim_token", nullable = false, length = 64)
    private String claimToken;

    @Column(name = "lease_expires_at", nullable = false)
    private LocalDateTime leaseExpiresAt;

    @Column(nullable = false)
    private LocalDateTime createTime;

    @Column(nullable = false)
    private LocalDateTime updateTime;

    public Long getShipmentId() {
        return shipmentId;
    }

    public void setShipmentId(Long shipmentId) {
        this.shipmentId = shipmentId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public void setRequestFingerprint(String requestFingerprint) {
        this.requestFingerprint = requestFingerprint;
    }

    public String getProviderToken() {
        return providerToken;
    }

    public void setProviderToken(String providerToken) {
        this.providerToken = providerToken;
    }

    public ShipmentClaimStatus getStatus() {
        return status;
    }

    public void setStatus(ShipmentClaimStatus status) {
        this.status = status;
    }

    public String getClaimToken() {
        return claimToken;
    }

    public void setClaimToken(String claimToken) {
        this.claimToken = claimToken;
    }

    public LocalDateTime getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(LocalDateTime leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
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
