package com.example.monkey.logistics.infrastructure;

import com.example.monkey.logistics.domain.ShipmentClaimReservation;
import com.example.monkey.logistics.domain.ShipmentClaimStatus;
import com.example.monkey.logistics.domain.ShipmentCreationClaim;
import com.example.monkey.logistics.domain.ShipmentCreationClaimStore;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** MySQL implementation of the durable one-create gate. */
@Component
@ConditionalOnProperty(name = "app.logistics.store", havingValue = "jpa", matchIfMissing = true)
public class JpaShipmentCreationClaimStore implements ShipmentCreationClaimStore {

    private static final Duration DEFAULT_LEASE = Duration.ofMinutes(5);

    private final LogisticsShipmentClaimRepository repository;

    public JpaShipmentCreationClaimStore(LogisticsShipmentClaimRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShipmentCreationClaim> findByTenantAndOrder(long tenantId, long orderId) {
        if (tenantId != TenantContext.currentTenantIdOrDefault()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Shipment claim tenant does not match request tenant");
        }
        return repository.findByTenantIdAndOrderId(tenantId, orderId).map(JpaShipmentCreationClaimStore::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ShipmentClaimReservation reserve(
            ShipmentCreationClaim candidate, LocalDateTime now, Duration leaseDuration) {
        requireTenant(candidate);
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        Duration effectiveLease = leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
                ? DEFAULT_LEASE
                : leaseDuration;
        LocalDateTime leaseExpiresAt = effectiveNow.plus(effectiveLease);
        int inserted = repository.insertIfAbsent(
                candidate.tenantId(),
                candidate.shipmentId(),
                candidate.orderId(),
                candidate.ownerUserId(),
                candidate.idempotencyKey(),
                candidate.requestFingerprint(),
                candidate.providerToken(),
                ShipmentClaimStatus.RESERVED.name(),
                candidate.claimToken(),
                leaseExpiresAt,
                candidate.createTime(),
                effectiveNow);
        if (inserted > 0) {
            return new ShipmentClaimReservation(
                    new ShipmentCreationClaim(
                            candidate.shipmentId(),
                            candidate.tenantId(),
                            candidate.orderId(),
                            candidate.ownerUserId(),
                            candidate.idempotencyKey(),
                            candidate.requestFingerprint(),
                            candidate.providerToken(),
                            ShipmentClaimStatus.RESERVED,
                            candidate.claimToken(),
                            leaseExpiresAt,
                            candidate.createTime(),
                            effectiveNow),
                    true);
        }

        var byKey = repository.findByTenantIdAndOwnerUserIdAndIdempotencyKey(
                candidate.tenantId(), candidate.ownerUserId(), candidate.idempotencyKey());
        var byOrder = repository.findByTenantIdAndOrderId(candidate.tenantId(), candidate.orderId());
        if (byKey.isPresent()) {
            assertCompatible(candidate, byKey.get());
        }
        if (byOrder.isPresent()) {
            assertCompatibleOrder(candidate, byOrder.get());
        }
        if (byKey.isPresent()
                && byOrder.isPresent()
                && !byKey.get().getShipmentId().equals(byOrder.get().getShipmentId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Shipment creation claim is inconsistent");
        }
        LogisticsShipmentClaimEntity existing = byKey.orElseGet(() -> byOrder.orElse(null));
        if (existing == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Shipment creation claim could not be verified");
        }
        ShipmentCreationClaim current = toDomain(existing);
        if (current.accepted() || current.leaseExpiresAt().isAfter(effectiveNow)) {
            return new ShipmentClaimReservation(current, false);
        }
        String nextClaimToken = UUID.randomUUID().toString();
        int renewed = repository.renewExpired(
                current.tenantId(),
                current.shipmentId(),
                current.claimToken(),
                nextClaimToken,
                leaseExpiresAt,
                effectiveNow,
                effectiveNow);
        if (renewed == 0) {
            LogisticsShipmentClaimEntity winner = repository
                    .findByTenantIdAndOrderId(candidate.tenantId(), candidate.orderId())
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.SERVICE_UNAVAILABLE, "Shipment creation claim could not be verified"));
            return new ShipmentClaimReservation(toDomain(winner), false);
        }
        return new ShipmentClaimReservation(current.withLease(nextClaimToken, leaseExpiresAt, effectiveNow), true);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseForRetry(ShipmentCreationClaim claim, LocalDateTime now) {
        requireTenant(claim);
        repository.releaseForRetry(
                claim.tenantId(), claim.shipmentId(), claim.claimToken(), now == null ? LocalDateTime.now() : now);
    }

    @Override
    public void markAccepted(ShipmentCreationClaim claim, LocalDateTime now) {
        requireTenant(claim);
        int updated = repository.markAccepted(
                claim.tenantId(), claim.shipmentId(), claim.claimToken(), now == null ? LocalDateTime.now() : now);
        if (updated != 1) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE, "Shipment creation claim was lost before acceptance");
        }
    }

    private static void requireTenant(ShipmentCreationClaim claim) {
        if (claim == null || claim.tenantId() != TenantContext.currentTenantIdOrDefault()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Shipment claim tenant does not match request tenant");
        }
    }

    private static void assertCompatible(ShipmentCreationClaim candidate, LogisticsShipmentClaimEntity existing) {
        if (candidate.orderId() != existing.getOrderId()
                || !candidate.requestFingerprint().equals(existing.getRequestFingerprint())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Idempotency-Key is already bound to a different shipment request");
        }
    }

    private static void assertCompatibleOrder(ShipmentCreationClaim candidate, LogisticsShipmentClaimEntity existing) {
        if (!candidate.requestFingerprint().equals(existing.getRequestFingerprint())
                || candidate.ownerUserId() != existing.getOwnerUserId()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Order already has a different shipment request");
        }
    }

    private static ShipmentCreationClaim toDomain(LogisticsShipmentClaimEntity entity) {
        return new ShipmentCreationClaim(
                entity.getShipmentId(),
                entity.getTenantId(),
                entity.getOrderId(),
                entity.getOwnerUserId(),
                entity.getIdempotencyKey(),
                entity.getRequestFingerprint(),
                entity.getProviderToken(),
                entity.getStatus(),
                entity.getClaimToken(),
                entity.getLeaseExpiresAt(),
                entity.getCreateTime(),
                entity.getUpdateTime());
    }
}
