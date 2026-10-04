package com.example.monkey.inventory.infrastructure;

import com.example.monkey.inventory.domain.InventoryReservationStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservationEntity, Long> {

    Optional<InventoryReservationEntity> findByReservationKey(String reservationKey);

    Optional<InventoryReservationEntity> findByTenantIdAndReservationKey(Long tenantId, String reservationKey);

    @Modifying(flushAutomatically = true)
    @Query(value = """
                    INSERT IGNORE INTO inventory_reservation (
                        id,
                        tenant_id,
                        reservation_key,
                        request_fingerprint,
                        sku_id,
                        warehouse_id,
                        order_id,
                        quantity,
                        status,
                        expires_at
                    ) VALUES (
                        :id,
                        :tenantId,
                        :reservationKey,
                        :requestFingerprint,
                        :skuId,
                        :warehouseId,
                        :orderId,
                        :quantity,
                        :status,
                        :expiresAt
                    )
                    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") Long id,
            @Param("tenantId") long tenantId,
            @Param("reservationKey") String reservationKey,
            @Param("requestFingerprint") String requestFingerprint,
            @Param("skuId") Long skuId,
            @Param("warehouseId") Long warehouseId,
            @Param("orderId") Long orderId,
            @Param("quantity") int quantity,
            @Param("status") String status,
            @Param("expiresAt") LocalDateTime expiresAt);

    boolean existsByReservationKey(String reservationKey);

    List<InventoryReservationEntity> findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
            InventoryReservationStatus status, LocalDateTime expiresAt);
}
