package com.example.monkey.inventory.infrastructure;

import com.example.monkey.inventory.domain.InventoryReservationStatus;
import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "inventory_reservation",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_inventory_reservation_key",
                        columnNames = {"tenant_id", "reservation_key"}))
public class InventoryReservationEntity extends TenantScopedJpaEntity {

    @Id
    private Long id;

    @Column(nullable = false, length = 128)
    private String reservationKey;

    @Column(name = "request_fingerprint", nullable = false, columnDefinition = "CHAR(64)")
    private String requestFingerprint;

    @Column(nullable = false)
    private Long skuId;

    @Column(nullable = false)
    private Long warehouseId;

    private Long orderId;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private InventoryReservationStatus status;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getReservationKey() {
        return reservationKey;
    }

    public void setReservationKey(String reservationKey) {
        this.reservationKey = reservationKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public void setRequestFingerprint(String requestFingerprint) {
        this.requestFingerprint = requestFingerprint;
    }

    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public InventoryReservationStatus getStatus() {
        return status;
    }

    public void setStatus(InventoryReservationStatus status) {
        this.status = status;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
