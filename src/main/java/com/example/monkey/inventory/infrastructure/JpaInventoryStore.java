package com.example.monkey.inventory.infrastructure;

import com.example.monkey.inventory.domain.InventoryOperation;
import com.example.monkey.inventory.domain.InventoryReconciliationReport;
import com.example.monkey.inventory.domain.InventoryReservation;
import com.example.monkey.inventory.domain.InventoryReservationStatus;
import com.example.monkey.inventory.domain.InventoryStockLedgerEntry;
import com.example.monkey.inventory.domain.InventoryStore;
import com.example.monkey.inventory.domain.WarehouseStock;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
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
        if (stock.version() < 1) {
            throw new IllegalStateException("Inventory stock version was not advanced");
        }
        long expectedVersion = stock.version() - 1;
        int updated = stockRepository.updateQuantities(
                stock.skuId(),
                stock.warehouseId(),
                TenantContext.currentTenantIdOrDefault(),
                expectedVersion,
                stock.availableQuantity(),
                stock.lockedQuantity(),
                stock.deductedQuantity(),
                stock.inTransitQuantity(),
                stock.safetyStock());
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "Inventory stock changed concurrently");
        }
        return findStock(stock.skuId(), stock.warehouseId())
                .orElseThrow(() -> new IllegalStateException("Inventory stock does not exist"));
    }

    @Override
    public Optional<InventoryReservation> findReservation(String reservationKey) {
        return reservationRepository
                .findByTenantIdAndReservationKey(TenantContext.currentTenantIdOrDefault(), reservationKey)
                .map(JpaInventoryStore::toDomain);
    }

    @Override
    public boolean saveReservationIfAbsent(InventoryReservation reservation) {
        long tenantId = TenantContext.currentTenantIdOrDefault();
        int inserted = reservationRepository.insertIfAbsent(
                reservation.id(),
                tenantId,
                reservation.reservationKey(),
                reservation.requestFingerprint(),
                reservation.skuId(),
                reservation.warehouseId(),
                reservation.orderId(),
                reservation.quantity(),
                reservation.status().name(),
                reservation.expiresAt());
        if (inserted > 0) {
            return true;
        }
        if (reservationRepository
                .findByTenantIdAndReservationKey(tenantId, reservation.reservationKey())
                .isEmpty()) {
            throw new IllegalStateException(
                    "Inventory reservation insert was ignored without an existing idempotency claim");
        }
        return false;
    }

    @Override
    public InventoryReservation saveReservation(InventoryReservation reservation) {
        InventoryReservationEntity entity = reservationRepository
                .findByTenantIdAndReservationKey(TenantContext.currentTenantIdOrDefault(), reservation.reservationKey())
                .orElseGet(InventoryReservationEntity::new);
        entity.setId(reservation.id());
        entity.setReservationKey(reservation.reservationKey());
        entity.setRequestFingerprint(reservation.requestFingerprint());
        entity.setSkuId(reservation.skuId());
        entity.setWarehouseId(reservation.warehouseId());
        entity.setOrderId(reservation.orderId());
        if (entity.getStatus() != null
                && entity.getStatus() != reservation.status()
                && entity.getStatus() != InventoryReservationStatus.RESERVED) {
            throw new BusinessException(ErrorCode.CONFLICT, "Inventory reservation changed concurrently");
        }
        entity.setQuantity(reservation.quantity());
        entity.setStatus(reservation.status());
        entity.setExpiresAt(reservation.expiresAt());
        return toDomain(reservationRepository.save(entity));
    }

    @Override
    public boolean recordLedger(InventoryStockLedgerEntry ledgerEntry) {
        long tenantId = TenantContext.currentTenantIdOrDefault();
        int inserted = ledgerRepository.insertIfAbsent(
                ledgerEntry.id(),
                ledgerEntry.skuId(),
                ledgerEntry.warehouseId(),
                ledgerEntry.reservationKey(),
                ledgerEntry.orderId(),
                ledgerEntry.operation().name(),
                ledgerEntry.quantity(),
                ledgerEntry.idempotencyKey(),
                LocalDateTime.now(),
                tenantId);
        if (inserted > 0) {
            return true;
        }
        if (ledgerRepository.findClaim(tenantId, ledgerEntry.idempotencyKey()).isEmpty()) {
            throw new IllegalStateException(
                    "Inventory ledger insert was ignored without an existing idempotency claim");
        }
        return false;
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
                entity.getRequestFingerprint(),
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
        entity.setRequestFingerprint(reservation.requestFingerprint());
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
