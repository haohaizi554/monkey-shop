package com.example.monkey.logistics.infrastructure;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LogisticsShipmentClaimRepository extends JpaRepository<LogisticsShipmentClaimEntity, Long> {

    Optional<LogisticsShipmentClaimEntity> findByTenantIdAndOrderId(Long tenantId, Long orderId);

    Optional<LogisticsShipmentClaimEntity> findByTenantIdAndOwnerUserIdAndIdempotencyKey(
            Long tenantId, Long ownerUserId, String idempotencyKey);

    @Modifying(flushAutomatically = true)
    @Query(value = """
                    INSERT IGNORE INTO logistics_shipment_claim (
                        tenant_id,
                        shipment_id,
                        order_id,
                        owner_user_id,
                        idempotency_key,
                        request_fingerprint,
                        provider_token,
                        status,
                        claim_token,
                        lease_expires_at,
                        create_time,
                        update_time
                    ) VALUES (
                        :tenantId,
                        :shipmentId,
                        :orderId,
                        :ownerUserId,
                        :idempotencyKey,
                        :requestFingerprint,
                        :providerToken,
                        :status,
                        :claimToken,
                        :leaseExpiresAt,
                        :createTime,
                        :updateTime
                    )
                    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("tenantId") long tenantId,
            @Param("shipmentId") long shipmentId,
            @Param("orderId") long orderId,
            @Param("ownerUserId") long ownerUserId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestFingerprint") String requestFingerprint,
            @Param("providerToken") String providerToken,
            @Param("status") String status,
            @Param("claimToken") String claimToken,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
            @Param("createTime") LocalDateTime createTime,
            @Param("updateTime") LocalDateTime updateTime);

    @Modifying(flushAutomatically = true)
    @Query(value = """
                    UPDATE logistics_shipment_claim
                    SET claim_token = :nextClaimToken,
                        lease_expires_at = :leaseExpiresAt,
                        update_time = :updateTime
                    WHERE tenant_id = :tenantId
                      AND shipment_id = :shipmentId
                      AND status = 'RESERVED'
                      AND claim_token = :expectedClaimToken
                      AND lease_expires_at <= :now
                    """, nativeQuery = true)
    int renewExpired(
            @Param("tenantId") long tenantId,
            @Param("shipmentId") long shipmentId,
            @Param("expectedClaimToken") String expectedClaimToken,
            @Param("nextClaimToken") String nextClaimToken,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
            @Param("updateTime") LocalDateTime updateTime,
            @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true)
    @Query(value = """
                    UPDATE logistics_shipment_claim
                    SET lease_expires_at = :now,
                        update_time = :now
                    WHERE tenant_id = :tenantId
                      AND shipment_id = :shipmentId
                      AND status = 'RESERVED'
                      AND claim_token = :claimToken
                    """, nativeQuery = true)
    int releaseForRetry(
            @Param("tenantId") long tenantId,
            @Param("shipmentId") long shipmentId,
            @Param("claimToken") String claimToken,
            @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true)
    @Query(value = """
                    UPDATE logistics_shipment_claim
                    SET status = 'ACCEPTED',
                        update_time = :now
                    WHERE tenant_id = :tenantId
                      AND shipment_id = :shipmentId
                      AND status = 'RESERVED'
                      AND claim_token = :claimToken
                    """, nativeQuery = true)
    int markAccepted(
            @Param("tenantId") long tenantId,
            @Param("shipmentId") long shipmentId,
            @Param("claimToken") String claimToken,
            @Param("now") LocalDateTime now);
}
