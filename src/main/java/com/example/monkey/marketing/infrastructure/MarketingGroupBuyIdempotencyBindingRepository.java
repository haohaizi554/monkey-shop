package com.example.monkey.marketing.infrastructure;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketingGroupBuyIdempotencyBindingRepository
        extends JpaRepository<MarketingGroupBuyIdempotencyBindingEntity, Long> {

    Optional<MarketingGroupBuyIdempotencyBindingEntity> findByTenantIdAndUserIdAndIdempotencyKey(
            Long tenantId, Long userId, String idempotencyKey);

    @Modifying(flushAutomatically = true)
    @Query(
            value = """
                    INSERT IGNORE INTO marketing_group_buy_idempotency_binding (
                        tenant_id,
                        user_id,
                        idempotency_key,
                        team_id,
                        request_fingerprint,
                        created_at,
                        updated_at
                    ) VALUES (
                        :tenantId,
                        :userId,
                        :idempotencyKey,
                        :teamId,
                        :requestFingerprint,
                        :createdAt,
                        :updatedAt
                    )
                    """,
            nativeQuery = true)
    int insertIfAbsent(
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("teamId") Long teamId,
            @Param("requestFingerprint") String requestFingerprint,
            @Param("createdAt") LocalDateTime createdAt,
            @Param("updatedAt") LocalDateTime updatedAt);
}
