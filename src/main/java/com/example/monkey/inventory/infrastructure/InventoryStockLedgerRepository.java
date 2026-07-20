package com.example.monkey.inventory.infrastructure;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryStockLedgerRepository extends JpaRepository<InventoryStockLedger, Long> {

    @Modifying(flushAutomatically = true)
    @Query(value = """
                    INSERT IGNORE INTO inventory_stock_ledger (
                        id,
                        sku_id,
                        warehouse_id,
                        reservation_key,
                        order_id,
                        operation,
                        quantity,
                        idempotency_key,
                        create_time,
                        tenant_id
                    ) VALUES (
                        :id,
                        :skuId,
                        :warehouseId,
                        :reservationKey,
                        :orderId,
                        :operation,
                        :quantity,
                        :idempotencyKey,
                        :createTime,
                        :tenantId
                    )
                    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") Long id,
            @Param("skuId") Long skuId,
            @Param("warehouseId") Long warehouseId,
            @Param("reservationKey") String reservationKey,
            @Param("orderId") Long orderId,
            @Param("operation") String operation,
            @Param("quantity") int quantity,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("createTime") LocalDateTime createTime,
            @Param("tenantId") long tenantId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            SELECT ledger
            FROM InventoryStockLedger ledger
            WHERE ledger.tenantId = :tenantId
              AND ledger.idempotencyKey = :idempotencyKey
            """)
    Optional<InventoryStockLedger> findClaim(
            @Param("tenantId") long tenantId, @Param("idempotencyKey") String idempotencyKey);
}
