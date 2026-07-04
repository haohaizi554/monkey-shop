package com.example.monkey.tenant.infrastructure;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantBillRepository extends JpaRepository<TenantBillEntity, Long> {

    Optional<TenantBillEntity> findByTenantIdAndBillingMonth(Long tenantId, String billingMonth);

    List<TenantBillEntity> findByTenantIdOrderByGeneratedAtDesc(Long tenantId);

    @Query(value = """
                    SELECT COUNT(*)
                    FROM orders
                    WHERE tenant_id = :tenantId
                      AND create_time >= :start
                      AND create_time < :end
                    """, nativeQuery = true)
    long countOrdersForTenant(
            @Param("tenantId") Long tenantId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query(value = """
                    SELECT COALESCE(SUM(paid_amount), 0)
                    FROM payment_order
                    WHERE tenant_id = :tenantId
                      AND status IN ('PAID', 'PARTIALLY_REFUNDED', 'REFUNDED')
                      AND create_time >= :start
                      AND create_time < :end
                    """, nativeQuery = true)
    BigDecimal sumPaidAmountForTenant(
            @Param("tenantId") Long tenantId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
