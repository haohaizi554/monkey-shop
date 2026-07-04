package com.example.monkey.order.infrastructure;

import com.example.monkey.shared.application.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    default int reserve(Long userId, String idempotencyKey, String requestHash, LocalDateTime expiresAt) {
        return reserve(TenantContext.currentTenantIdOrDefault(), userId, idempotencyKey, requestHash, expiresAt);
    }

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO idempotency_record
                (tenant_id, user_id, idempotency_key, request_hash, status, expires_at, created_at)
            VALUES
                (:tenantId, :userId, :idempotencyKey, :requestHash, 'PROCESSING', :expiresAt, CURRENT_TIMESTAMP(6))
            """, nativeQuery = true)
    int reserve(
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("expiresAt") LocalDateTime expiresAt);

    default Optional<IdempotencyRecord> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey) {
        return findByTenantIdAndUserIdAndIdempotencyKey(
                TenantContext.currentTenantIdOrDefault(), userId, idempotencyKey);
    }

    Optional<IdempotencyRecord> findByTenantIdAndUserIdAndIdempotencyKey(
            Long tenantId, Long userId, String idempotencyKey);

    default int complete(Long userId, String idempotencyKey, Long orderId) {
        return complete(TenantContext.currentTenantIdOrDefault(), userId, idempotencyKey, orderId);
    }

    @Modifying
    @Query("""
            update IdempotencyRecord record
            set record.orderId = :orderId, record.status = 'COMPLETED'
            where record.tenantId = :tenantId
                and record.userId = :userId
                and record.idempotencyKey = :idempotencyKey
            """)
    int complete(
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("orderId") Long orderId);
}
