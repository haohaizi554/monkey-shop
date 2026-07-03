package com.example.monkey.inventory.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InventoryStore {

    Optional<WarehouseStock> findStock(Long skuId, Long warehouseId);

    List<WarehouseStock> findStocksBySku(Long skuId);

    WarehouseStock saveStock(WarehouseStock stock);

    Optional<InventoryReservation> findReservation(String reservationKey);

    boolean saveReservationIfAbsent(InventoryReservation reservation);

    InventoryReservation saveReservation(InventoryReservation reservation);

    boolean recordLedger(InventoryStockLedgerEntry ledgerEntry);

    List<InventoryReservation> findExpiredReservations(LocalDateTime now, int limit);

    InventoryReconciliationReport reconcile();
}
