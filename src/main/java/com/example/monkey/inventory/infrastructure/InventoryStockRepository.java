package com.example.monkey.inventory.infrastructure;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryStockRepository extends JpaRepository<InventoryStock, Long> {

    Optional<InventoryStock> findBySkuIdAndWarehouseId(Long skuId, Long warehouseId);

    List<InventoryStock> findBySkuIdOrderByAvailableQuantityDesc(Long skuId);
}
