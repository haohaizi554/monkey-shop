package com.example.monkey.cart.infrastructure;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartCleanupIntentRepository extends JpaRepository<CartCleanupIntentEntity, Long> {

    @Query(value = """
                    SELECT checkout_id
                    FROM cart_cleanup_intent
                    WHERE tenant_id = :tenantId
                      AND ((status = 'PENDING' AND next_attempt_at <= :now)
                        OR (status = 'PROCESSING' AND lease_expires_at <= :now))
                    ORDER BY create_time
                    LIMIT 100
                    """, nativeQuery = true)
    List<Long> findReadyCheckoutIds(@Param("tenantId") Long tenantId, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
                    UPDATE cart_cleanup_intent
                    SET status = 'PROCESSING',
                        claim_token = :claimToken,
                        lease_expires_at = :leaseExpiresAt,
                        attempt_count = attempt_count + 1,
                        update_time = :now
                    WHERE checkout_id = :checkoutId
                      AND tenant_id = :tenantId
                      AND ((status = 'PENDING' AND next_attempt_at <= :now)
                        OR (status = 'PROCESSING' AND lease_expires_at <= :now))
                    """, nativeQuery = true)
    int claim(
            @Param("checkoutId") Long checkoutId,
            @Param("tenantId") Long tenantId,
            @Param("claimToken") String claimToken,
            @Param("now") LocalDateTime now,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
                    UPDATE cart_cleanup_intent
                    SET status = 'COMPLETED',
                        claim_token = NULL,
                        lease_expires_at = NULL,
                        last_error = NULL,
                        completed_at = :now,
                        update_time = :now
                    WHERE checkout_id = :checkoutId
                      AND tenant_id = :tenantId
                      AND status = 'PROCESSING'
                      AND claim_token = :claimToken
                    """, nativeQuery = true)
    int completeClaim(
            @Param("checkoutId") Long checkoutId,
            @Param("tenantId") Long tenantId,
            @Param("claimToken") String claimToken,
            @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
                    UPDATE cart_cleanup_intent
                    SET status = 'PENDING',
                        claim_token = NULL,
                        lease_expires_at = NULL,
                        next_attempt_at = :nextAttemptAt,
                        last_error = :error,
                        completed_at = NULL,
                        update_time = :now
                    WHERE checkout_id = :checkoutId
                      AND tenant_id = :tenantId
                      AND status = 'PROCESSING'
                      AND claim_token = :claimToken
                    """, nativeQuery = true)
    int failClaim(
            @Param("checkoutId") Long checkoutId,
            @Param("tenantId") Long tenantId,
            @Param("claimToken") String claimToken,
            @Param("now") LocalDateTime now,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("error") String error);
}
