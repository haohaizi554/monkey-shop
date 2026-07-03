package com.example.monkey.inventory.domain;

import java.time.LocalDateTime;

public record InventoryReservation(
        Long id,
        String reservationKey,
        Long skuId,
        Long warehouseId,
        Long orderId,
        int quantity,
        InventoryReservationStatus status,
        LocalDateTime expiresAt) {
    public InventoryReservation {
        if (id == null) {
            throw new IllegalArgumentException("reservation id is required");
        }
        if (reservationKey == null || reservationKey.isBlank()) {
            throw new IllegalArgumentException("reservation key is required");
        }
        if (skuId == null) {
            throw new IllegalArgumentException("SKU id is required");
        }
        if (warehouseId == null) {
            throw new IllegalArgumentException("warehouse id is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("reservation quantity must be positive");
        }
        status = status == null ? InventoryReservationStatus.RESERVED : status;
        if (expiresAt == null) {
            throw new IllegalArgumentException("reservation expiry is required");
        }
    }

    public boolean activeAt(LocalDateTime now) {
        return InventoryReservationStatus.RESERVED.equals(status) && expiresAt.isAfter(now);
    }

    public boolean expiredAt(LocalDateTime now) {
        return InventoryReservationStatus.RESERVED.equals(status) && !expiresAt.isAfter(now);
    }

    public InventoryReservation release() {
        if (InventoryReservationStatus.RELEASED.equals(status)) {
            return this;
        }
        requireReserved("release");
        return withStatus(InventoryReservationStatus.RELEASED);
    }

    public InventoryReservation deduct() {
        if (InventoryReservationStatus.DEDUCTED.equals(status)) {
            return this;
        }
        requireReserved("deduct");
        return withStatus(InventoryReservationStatus.DEDUCTED);
    }

    public InventoryReservation expire() {
        if (InventoryReservationStatus.EXPIRED.equals(status)) {
            return this;
        }
        requireReserved("expire");
        return withStatus(InventoryReservationStatus.EXPIRED);
    }

    private InventoryReservation withStatus(InventoryReservationStatus nextStatus) {
        return new InventoryReservation(
                id, reservationKey, skuId, warehouseId, orderId, quantity, nextStatus, expiresAt);
    }

    private void requireReserved(String action) {
        if (!InventoryReservationStatus.RESERVED.equals(status)) {
            throw new IllegalStateException("Cannot " + action + " reservation in status " + status);
        }
    }
}
