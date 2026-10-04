package com.example.monkey.inventory.infrastructure;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryStockRepository extends JpaRepository<InventoryStock, Long> {

    Optional<InventoryStock> findBySkuIdAndWarehouseId(Long skuId, Long warehouseId);

    List<InventoryStock> findBySkuIdOrderByAvailableQuantityDesc(Long skuId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
                    UPDATE inventory_stock
                    SET available_quantity = :availableQuantity,
                        locked_quantity = :lockedQuantity,
                        deducted_quantity = :deductedQuantity,
                        in_transit_quantity = :inTransitQuantity,
                        safety_stock = :safetyStock,
                        version = version + 1
                    WHERE sku_id = :skuId
                      AND warehouse_id = :warehouseId
                      AND tenant_id = :tenantId
                      AND version = :expectedVersion
                    """, nativeQuery = true)
    int updateQuantities(
            @Param("skuId") Long skuId,
            @Param("warehouseId") Long warehouseId,
            @Param("tenantId") long tenantId,
            @Param("expectedVersion") long expectedVersion,
            @Param("availableQuantity") int availableQuantity,
            @Param("lockedQuantity") int lockedQuantity,
            @Param("deductedQuantity") int deductedQuantity,
            @Param("inTransitQuantity") int inTransitQuantity,
            @Param("safetyStock") int safetyStock);
}
