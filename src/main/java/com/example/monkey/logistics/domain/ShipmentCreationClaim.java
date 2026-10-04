package com.example.monkey.logistics.domain;

import java.time.LocalDateTime;

/**
 * Durable identity for a shipment creation attempt.
 *
 * <p>The claim is intentionally separate from the logistics projection. It is committed before the carrier
 * call, so a second idempotency key cannot start another provider create while the first call is in flight. The
 * provider token is stable for the lifetime of the claim and must be passed to an idempotent carrier adapter.
 */
public record ShipmentCreationClaim(
        Long shipmentId,
        long tenantId,
        long orderId,
        long ownerUserId,
        String idempotencyKey,
        String requestFingerprint,
        String providerToken,
        ShipmentClaimStatus status,
        String claimToken,
        LocalDateTime leaseExpiresAt,
        LocalDateTime createTime,
        LocalDateTime updateTime) {

    public ShipmentCreationClaim {
        if (shipmentId == null || shipmentId <= 0) {
            throw new IllegalArgumentException("shipment claim identity is required");
        }
        if (tenantId <= 0 || orderId <= 0 || ownerUserId <= 0) {
            throw new IllegalArgumentException("shipment claim tenant/order/owner identity is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("shipment claim idempotency key is required");
        }
        if (requestFingerprint == null || requestFingerprint.isBlank()) {
            throw new IllegalArgumentException("shipment claim request fingerprint is required");
        }
        if (providerToken == null || providerToken.isBlank()) {
            throw new IllegalArgumentException("shipment claim provider token is required");
        }
        status = status == null ? ShipmentClaimStatus.RESERVED : status;
        if (claimToken == null || claimToken.isBlank()) {
            throw new IllegalArgumentException("shipment claim token is required");
        }
        if (leaseExpiresAt == null || createTime == null || updateTime == null) {
            throw new IllegalArgumentException("shipment claim timestamps are required");
        }
        idempotencyKey = idempotencyKey.strip();
        requestFingerprint = requestFingerprint.strip();
        providerToken = providerToken.strip();
        claimToken = claimToken.strip();
    }

    public boolean accepted() {
        return ShipmentClaimStatus.ACCEPTED.equals(status);
    }

    public boolean sameOrder(long expectedTenantId, long expectedOrderId) {
        return tenantId == expectedTenantId && orderId == expectedOrderId;
    }

    public boolean sameRequest(
            long expectedTenantId, long expectedOrderId, long expectedOwnerUserId, String expectedFingerprint) {
        return tenantId == expectedTenantId
                && orderId == expectedOrderId
                && ownerUserId == expectedOwnerUserId
                && requestFingerprint.equals(expectedFingerprint);
    }

    public ShipmentCreationClaim withLease(String nextClaimToken, LocalDateTime nextLeaseExpiresAt, LocalDateTime now) {
        return new ShipmentCreationClaim(
                shipmentId,
                tenantId,
                orderId,
                ownerUserId,
                idempotencyKey,
                requestFingerprint,
                providerToken,
                ShipmentClaimStatus.RESERVED,
                nextClaimToken,
                nextLeaseExpiresAt,
                createTime,
                now);
    }

    public ShipmentCreationClaim acceptedAt(LocalDateTime now) {
        return new ShipmentCreationClaim(
                shipmentId,
                tenantId,
                orderId,
                ownerUserId,
                idempotencyKey,
                requestFingerprint,
                providerToken,
                ShipmentClaimStatus.ACCEPTED,
                claimToken,
                leaseExpiresAt,
                createTime,
                now);
    }
}
