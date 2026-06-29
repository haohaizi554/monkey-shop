package com.example.monkey.order.infrastructure;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO idempotency_record
                (user_id, idempotency_key, request_hash, status, expires_at, created_at)
            VALUES
                (:userId, :idempotencyKey, :requestHash, 'PROCESSING', :expiresAt, CURRENT_TIMESTAMP(6))
            """, nativeQuery = true)
    int reserve(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("expiresAt") LocalDateTime expiresAt);

    Optional<IdempotencyRecord> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    @Modifying
    @Query("""
            update IdempotencyRecord record
            set record.orderId = :orderId, record.status = 'COMPLETED'
            where record.userId = :userId and record.idempotencyKey = :idempotencyKey
            """)
    int complete(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("orderId") Long orderId);
}
