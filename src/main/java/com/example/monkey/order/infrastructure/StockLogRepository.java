package com.example.monkey.order.infrastructure;

import com.example.monkey.shared.application.tenant.TenantContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockLogRepository extends JpaRepository<StockLog, Long> {

    default int recordRestore(Long orderId, Long productId) {
        return recordRestore(TenantContext.currentTenantIdOrDefault(), orderId, productId);
    }

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO stock_log (tenant_id, order_id, product_id, direction, created_at)
            VALUES (:tenantId, :orderId, :productId, 'RESTORE', CURRENT_TIMESTAMP(6))
            """, nativeQuery = true)
    int recordRestore(
            @Param("tenantId") Long tenantId, @Param("orderId") Long orderId, @Param("productId") Long productId);
}
