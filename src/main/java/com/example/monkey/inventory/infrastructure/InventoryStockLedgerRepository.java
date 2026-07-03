package com.example.monkey.inventory.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryStockLedgerRepository extends JpaRepository<InventoryStockLedger, Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);
}
