package com.example.monkey.inventory.infrastructure;

import com.example.monkey.inventory.domain.InventoryOperation;
import com.example.monkey.inventory.domain.InventoryReconciliationReport;
import com.example.monkey.inventory.domain.InventoryReservation;
import com.example.monkey.inventory.domain.InventoryReservationStatus;
import com.example.monkey.inventory.domain.InventoryStockLedgerEntry;
import com.example.monkey.inventory.domain.InventoryStore;
import com.example.monkey.inventory.domain.WarehouseStock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.inventory.store", havingValue = "jpa", matchIfMissing = true)
public class JpaInventoryStore implements InventoryStore {

    private final InventoryStockRepository stockRepository;
    private final InventoryWarehouseRepository warehouseRepository;
    private final InventoryReservationRepository reservationRepository;
    private final InventoryStockLedgerRepository ledgerRepository;

    public JpaInventoryStore(
            InventoryStockRepository stockRepository,
            InventoryWarehouseRepository warehouseRepository,
            InventoryReservationRepository reservationRepository,
            InventoryStockLedgerRepository ledgerRepository) {
        this.stockRepository = stockRepository;
        this.warehouseRepository = warehouseRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
    }

    @Override
    public Optional<WarehouseStock> findStock(Long skuId, Long warehouseId) {
        return stockRepository.findBySkuIdAndWarehouseId(skuId, warehouseId).map(this::toDomain);
    }

    @Override
    public List<WarehouseStock> findStocksBySku(Long skuId) {
        return stockRepository.findBySkuIdOrderByAvailableQuantityDesc(skuId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public WarehouseStock saveStock(WarehouseStock stock) {
        InventoryStock entity = stockRepository
                .findBySkuIdAndWarehouseId(stock.skuId(), stock.warehouseId())
                .orElseThrow(() -> new IllegalStateException("Inventory stock does not exist"));
        entity.setAvailableQuantity(stock.availableQuantity());
        entity.setLockedQuantity(stock.lockedQuantity());
        entity.setDeductedQuantity(stock.deductedQuantity());
        entity.setInTransitQuantity(stock.inTransitQuantity());
        entity.setSafetyStock(stock.safetyStock());
        return toDomain(stockRepository.save(entity));
    }

    @Override
    public Optional<InventoryReservation> findReservation(String reservationKey) {
        return reservationRepository.findByReservationKey(reservationKey).map(JpaInventoryStore::toDomain);
    }

    @Override
    public boolean saveReservationIfAbsent(InventoryReservation reservation) {
        if (reservationRepository.existsByReservationKey(reservation.reservationKey())) {
            return false;
        }
        reservationRepository.save(toEntity(reservation));
        return true;
    }

    @Override
    public InventoryReservation saveReservation(InventoryReservation reservation) {
        InventoryReservationEntity entity = reservationRepository
                .findByReservationKey(reservation.reservationKey())
                .orElseGet(InventoryReservationEntity::new);
        entity.setId(reservation.id());
        entity.setReservationKey(reservation.reservationKey());
        entity.setSkuId(reservation.skuId());
        entity.setWarehouseId(reservation.warehouseId());
        entity.setOrderId(reservation.orderId());
        entity.setQuantity(reservation.quantity());
        entity.setStatus(reservation.status());
        entity.setExpiresAt(reservation.expiresAt());
        return toDomain(reservationRepository.save(entity));
    }

    @Override
    public boolean recordLedger(InventoryStockLedgerEntry ledgerEntry) {
        if (ledgerRepository.existsByIdempotencyKey(ledgerEntry.idempotencyKey())) {
            return false;
        }
        InventoryStockLedger entity = new InventoryStockLedger();
        entity.setId(ledgerEntry.id());
        entity.setSkuId(ledgerEntry.skuId());
        entity.setWarehouseId(ledgerEntry.warehouseId());
        entity.setReservationKey(ledgerEntry.reservationKey());
        entity.setOrderId(ledgerEntry.orderId());
        entity.setOperation(ledgerEntry.operation());
        entity.setQuantity(ledgerEntry.quantity());
        entity.setIdempotencyKey(ledgerEntry.idempotencyKey());
        entity.setCreateTime(LocalDateTime.now());
        ledgerRepository.save(entity);
        return true;
    }

    @Override
    public List<InventoryReservation> findExpiredReservations(LocalDateTime now, int limit) {
        return reservationRepository
                .findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(InventoryReservationStatus.RESERVED, now)
                .stream()
                .limit(limit)
                .map(JpaInventoryStore::toDomain)
                .toList();
    }

    @Override
    public InventoryReconciliationReport reconcile() {
        Map<StockKey, LedgerBalance> balances = new HashMap<>();
        for (InventoryStockLedger ledger : ledgerRepository.findAll()) {
            balances.computeIfAbsent(
                            new StockKey(ledger.getSkuId(), ledger.getWarehouseId()), ignored -> new LedgerBalance())
                    .apply(ledger.getOperation(), ledger.getQuantity());
        }
        List<InventoryReconciliationReport.Discrepancy> discrepancies = new ArrayList<>();
        for (InventoryStock stock : stockRepository.findAll()) {
            LedgerBalance balance =
                    balances.getOrDefault(new StockKey(stock.getSkuId(), stock.getWarehouseId()), new LedgerBalance());
            if (stock.getLockedQuantity() != balance.locked || stock.getDeductedQuantity() != balance.deducted) {
                discrepancies.add(new InventoryReconciliationReport.Discrepancy(
                        stock.getSkuId(),
                        stock.getWarehouseId(),
                        stock.getLockedQuantity(),
                        balance.locked,
                        stock.getDeductedQuantity(),
                        balance.deducted));
            }
        }
        return new InventoryReconciliationReport(discrepancies);
    }

    private WarehouseStock toDomain(InventoryStock stock) {
        InventoryWarehouse warehouse =
                warehouseRepository.findById(stock.getWarehouseId()).orElse(null);
        return new WarehouseStock(
                stock.getSkuId(),
                stock.getWarehouseId(),
                warehouse == null ? null : warehouse.getCode(),
                warehouse == null ? null : warehouse.getProvince(),
                stock.getAvailableQuantity(),
                stock.getLockedQuantity(),
                stock.getDeductedQuantity(),
                stock.getInTransitQuantity(),
                stock.getSafetyStock(),
                stock.getVersion() == null ? 0L : stock.getVersion());
    }

    private static InventoryReservation toDomain(InventoryReservationEntity entity) {
        return new InventoryReservation(
                entity.getId(),
                entity.getReservationKey(),
                entity.getSkuId(),
                entity.getWarehouseId(),
                entity.getOrderId(),
                entity.getQuantity(),
                entity.getStatus(),
                entity.getExpiresAt());
    }

    private static InventoryReservationEntity toEntity(InventoryReservation reservation) {
        InventoryReservationEntity entity = new InventoryReservationEntity();
        entity.setId(reservation.id());
        entity.setReservationKey(reservation.reservationKey());
        entity.setSkuId(reservation.skuId());
        entity.setWarehouseId(reservation.warehouseId());
        entity.setOrderId(reservation.orderId());
        entity.setQuantity(reservation.quantity());
        entity.setStatus(reservation.status());
        entity.setExpiresAt(reservation.expiresAt());
        return entity;
    }

    private record StockKey(Long skuId, Long warehouseId) {}

    private static final class LedgerBalance {
        private int locked;
        private int deducted;

        private void apply(InventoryOperation operation, int quantity) {
            switch (operation) {
                case RESERVE -> locked += quantity;
                case RELEASE -> locked -= quantity;
                case DEDUCT -> {
                    locked -= quantity;
                    deducted += quantity;
                }
                case COMPENSATE -> deducted -= quantity;
            }
        }
    }
}
