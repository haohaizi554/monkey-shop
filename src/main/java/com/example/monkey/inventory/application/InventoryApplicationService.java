package com.example.monkey.inventory.application;

import com.example.monkey.inventory.application.dto.InventoryCompensateRequestDto;
import com.example.monkey.inventory.application.dto.InventoryReconciliationResponseDto;
import com.example.monkey.inventory.application.dto.InventoryReservationResponseDto;
import com.example.monkey.inventory.application.dto.InventoryReserveRequestDto;
import com.example.monkey.inventory.application.dto.WarehouseStockResponseDto;
import com.example.monkey.inventory.domain.InventoryLockManager;
import com.example.monkey.inventory.domain.InventoryOperation;
import com.example.monkey.inventory.domain.InventoryReconciliationReport;
import com.example.monkey.inventory.domain.InventoryReservation;
import com.example.monkey.inventory.domain.InventoryReservationStatus;
import com.example.monkey.inventory.domain.InventoryStockLedgerEntry;
import com.example.monkey.inventory.domain.InventoryStore;
import com.example.monkey.inventory.domain.WarehouseStock;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.domain.inventory.InventoryReservationLifecycle;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class InventoryApplicationService implements InventoryReservationLifecycle {

    private static final Duration DEFAULT_RESERVATION_TTL = Duration.ofMinutes(15);
    private static final int EXPIRY_BATCH_SIZE = 100;
    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final InventoryStore inventoryStore;
    private final InventoryLockManager lockManager;
    private final IdGenerator idGenerator;
    private final AuditService auditService;
    private final Clock clock;
    private final Duration reservationTtl;

    @Autowired
    public InventoryApplicationService(
            InventoryStore inventoryStore,
            InventoryLockManager lockManager,
            IdGenerator idGenerator,
            AuditService auditService,
            @Value("${app.inventory.reservation-ttl:PT15M}") Duration reservationTtl) {
        this(inventoryStore, lockManager, idGenerator, auditService, Clock.systemDefaultZone(), reservationTtl);
    }

    InventoryApplicationService(
            InventoryStore inventoryStore,
            InventoryLockManager lockManager,
            IdGenerator idGenerator,
            AuditService auditService,
            Clock clock,
            Duration reservationTtl) {
        this.inventoryStore = inventoryStore;
        this.lockManager = lockManager;
        this.idGenerator = idGenerator;
        this.auditService = auditService;
        this.clock = clock;
        this.reservationTtl = reservationTtl == null ? DEFAULT_RESERVATION_TTL : reservationTtl;
    }

    @WithSpan("inventory.reserve")
    @Transactional
    public InventoryReservationResponseDto reserve(InventoryReserveRequestDto request) {
        String reservationKey = normalizeKey(request.reservationKey(), "reservation key");
        Optional<InventoryReservation> existing = inventoryStore.findReservation(reservationKey);
        if (existing.isPresent()) {
            return toReservationResponse(
                    existing.get(),
                    findStock(existing.get().skuId(), existing.get().warehouseId()));
        }
        WarehouseStock stock =
                selectStock(request.skuId(), request.warehouseId(), request.province(), request.quantity());
        return lockManager.withStockLock(
                stock.skuId(), stock.warehouseId(), () -> reserveLocked(request, reservationKey, stock));
    }

    @WithSpan("inventory.release")
    @Transactional
    public InventoryReservationResponseDto release(String reservationKey) {
        InventoryReservation reservation = requireReservation(reservationKey);
        if (InventoryReservationStatus.RELEASED.equals(reservation.status())) {
            return toReservationResponse(reservation, findStock(reservation.skuId(), reservation.warehouseId()));
        }
        if (!InventoryReservationStatus.RESERVED.equals(reservation.status())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Reservation cannot be released from " + reservation.status());
        }
        return lockManager.withStockLock(reservation.skuId(), reservation.warehouseId(), () -> {
            WarehouseStock stock = findStock(reservation.skuId(), reservation.warehouseId());
            WarehouseStock savedStock = inventoryStore.saveStock(stock.release(reservation.quantity()));
            InventoryReservation savedReservation = inventoryStore.saveReservation(reservation.release());
            recordLedger(savedReservation, InventoryOperation.RELEASE, "release:" + savedReservation.reservationKey());
            audit(AuditService.INVENTORY_RELEASED, savedReservation, "quantity=" + savedReservation.quantity());
            return toReservationResponse(savedReservation, savedStock);
        });
    }

    @WithSpan("inventory.deduct")
    @Transactional
    public InventoryReservationResponseDto deduct(String reservationKey) {
        InventoryReservation reservation = requireReservation(reservationKey);
        if (InventoryReservationStatus.DEDUCTED.equals(reservation.status())) {
            return toReservationResponse(reservation, findStock(reservation.skuId(), reservation.warehouseId()));
        }
        if (!InventoryReservationStatus.RESERVED.equals(reservation.status())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Reservation cannot be deducted from " + reservation.status());
        }
        return lockManager.withStockLock(reservation.skuId(), reservation.warehouseId(), () -> {
            WarehouseStock stock = findStock(reservation.skuId(), reservation.warehouseId());
            WarehouseStock savedStock = inventoryStore.saveStock(stock.deduct(reservation.quantity()));
            InventoryReservation savedReservation = inventoryStore.saveReservation(reservation.deduct());
            recordLedger(savedReservation, InventoryOperation.DEDUCT, "deduct:" + savedReservation.reservationKey());
            audit(AuditService.INVENTORY_DEDUCTED, savedReservation, "quantity=" + savedReservation.quantity());
            return toReservationResponse(savedReservation, savedStock);
        });
    }

    @Override
    @Transactional
    public void deductReservation(String reservationKey) {
        deduct(reservationKey);
    }

    @WithSpan("inventory.compensate")
    @Transactional
    public WarehouseStockResponseDto compensate(InventoryCompensateRequestDto request) {
        String idempotencyKey = normalizeKey(request.idempotencyKey(), "idempotency key");
        return lockManager.withStockLock(request.skuId(), request.warehouseId(), () -> {
            WarehouseStock stock = findStock(request.skuId(), request.warehouseId());
            InventoryStockLedgerEntry ledgerEntry = new InventoryStockLedgerEntry(
                    idGenerator.nextId(),
                    request.skuId(),
                    request.warehouseId(),
                    null,
                    request.orderId(),
                    InventoryOperation.COMPENSATE,
                    request.quantity(),
                    "compensate:" + idempotencyKey);
            if (!inventoryStore.recordLedger(ledgerEntry)) {
                return InventoryDtoAssembler.toResponse(stock);
            }
            WarehouseStock savedStock = inventoryStore.saveStock(stock.compensate(request.quantity()));
            auditService.record(
                    AuditService.INVENTORY_COMPENSATED,
                    AuditService.OUTCOME_SUCCESS,
                    null,
                    SYSTEM_ACTOR,
                    "inventory:" + request.skuId() + ":" + request.warehouseId(),
                    null,
                    "quantity=" + request.quantity() + ",orderId=" + request.orderId());
            return InventoryDtoAssembler.toResponse(savedStock);
        });
    }

    @Override
    @Transactional
    public void compensateReturn(Long skuId, Long warehouseId, Long orderId, int quantity, String idempotencyKey) {
        compensate(new InventoryCompensateRequestDto(skuId, warehouseId, orderId, quantity, idempotencyKey));
    }

    @WithSpan("inventory.stock")
    @Transactional(readOnly = true)
    public List<WarehouseStockResponseDto> stocks(Long skuId) {
        return inventoryStore.findStocksBySku(skuId).stream()
                .map(InventoryDtoAssembler::toResponse)
                .toList();
    }

    @WithSpan("inventory.reconcile")
    @Transactional(readOnly = true)
    public InventoryReconciliationResponseDto reconcile() {
        InventoryReconciliationReport report = inventoryStore.reconcile();
        auditService.record(
                AuditService.INVENTORY_RECONCILED,
                report.balanced() ? AuditService.OUTCOME_SUCCESS : AuditService.OUTCOME_FAILURE,
                null,
                SYSTEM_ACTOR,
                "inventory-reconciliation",
                null,
                "discrepancies=" + report.discrepancies().size());
        return InventoryDtoAssembler.toResponse(report);
    }

    @Scheduled(fixedDelayString = "${app.inventory.release-expired-delay:PT1M}")
    @SchedulerLock(
            name = "inventory-release-expired-reservations",
            lockAtMostFor = "${app.inventory.release-lock-at-most-for:PT10M}")
    @Transactional
    public void releaseExpiredReservationsScheduled() {
        releaseExpiredReservations();
    }

    @Transactional
    public int releaseExpiredReservations() {
        LocalDateTime now = LocalDateTime.now(clock);
        int released = 0;
        for (InventoryReservation reservation : inventoryStore.findExpiredReservations(now, EXPIRY_BATCH_SIZE)) {
            releaseExpiredReservation(reservation);
            released++;
        }
        return released;
    }

    private InventoryReservationResponseDto reserveLocked(
            InventoryReserveRequestDto request, String reservationKey, WarehouseStock selectedStock) {
        Optional<InventoryReservation> existing = inventoryStore.findReservation(reservationKey);
        if (existing.isPresent()) {
            return toReservationResponse(
                    existing.get(),
                    findStock(existing.get().skuId(), existing.get().warehouseId()));
        }
        WarehouseStock stock = findStock(selectedStock.skuId(), selectedStock.warehouseId());
        if (!stock.canReserve(request.quantity())) {
            throw new BusinessException(ErrorCode.OUT_OF_STOCK, "Insufficient stock");
        }
        WarehouseStock savedStock = inventoryStore.saveStock(stock.reserve(request.quantity()));
        InventoryReservation reservation = new InventoryReservation(
                idGenerator.nextId(),
                reservationKey,
                stock.skuId(),
                stock.warehouseId(),
                request.orderId(),
                request.quantity(),
                InventoryReservationStatus.RESERVED,
                LocalDateTime.now(clock).plus(reservationTtl));
        if (!inventoryStore.saveReservationIfAbsent(reservation)) {
            return toReservationResponse(requireReservation(reservationKey), savedStock);
        }
        recordLedger(reservation, InventoryOperation.RESERVE, "reserve:" + reservationKey);
        audit(AuditService.INVENTORY_RESERVED, reservation, "quantity=" + reservation.quantity());
        return toReservationResponse(reservation, savedStock);
    }

    private void releaseExpiredReservation(InventoryReservation reservation) {
        if (!reservation.expiredAt(LocalDateTime.now(clock))) {
            return;
        }
        lockManager.withStockLock(reservation.skuId(), reservation.warehouseId(), () -> {
            InventoryReservation current = requireReservation(reservation.reservationKey());
            if (!current.expiredAt(LocalDateTime.now(clock))) {
                return null;
            }
            WarehouseStock stock = findStock(current.skuId(), current.warehouseId());
            inventoryStore.saveStock(stock.release(current.quantity()));
            InventoryReservation expired = inventoryStore.saveReservation(current.expire());
            recordLedger(expired, InventoryOperation.RELEASE, "expire:" + expired.reservationKey());
            audit(AuditService.INVENTORY_RELEASED, expired, "expired=true,quantity=" + expired.quantity());
            return null;
        });
    }

    private WarehouseStock selectStock(Long skuId, Long warehouseId, String province, int quantity) {
        if (warehouseId != null) {
            WarehouseStock stock = findStock(skuId, warehouseId);
            if (!stock.canReserve(quantity)) {
                throw new BusinessException(ErrorCode.OUT_OF_STOCK, "Insufficient stock in requested warehouse");
            }
            return stock;
        }
        return inventoryStore.findStocksBySku(skuId).stream()
                .filter(stock -> stock.canReserve(quantity))
                .sorted(routeComparator(province))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.OUT_OF_STOCK, "Insufficient stock"));
    }

    private static Comparator<WarehouseStock> routeComparator(String province) {
        return Comparator.<WarehouseStock>comparingInt(
                        stock -> province != null && province.equals(stock.province()) ? 0 : 1)
                .thenComparing(Comparator.comparingInt(WarehouseStock::availableQuantity)
                        .reversed())
                .thenComparing(WarehouseStock::warehouseId);
    }

    private InventoryReservation requireReservation(String reservationKey) {
        return inventoryStore
                .findReservation(normalizeKey(reservationKey, "reservation key"))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Inventory reservation does not exist"));
    }

    private WarehouseStock findStock(Long skuId, Long warehouseId) {
        return inventoryStore
                .findStock(skuId, warehouseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Inventory stock does not exist"));
    }

    private void recordLedger(InventoryReservation reservation, InventoryOperation operation, String idempotencyKey) {
        inventoryStore.recordLedger(new InventoryStockLedgerEntry(
                idGenerator.nextId(),
                reservation.skuId(),
                reservation.warehouseId(),
                reservation.reservationKey(),
                reservation.orderId(),
                operation,
                reservation.quantity(),
                idempotencyKey));
    }

    private void audit(String eventType, InventoryReservation reservation, String detail) {
        auditService.record(
                eventType,
                AuditService.OUTCOME_SUCCESS,
                null,
                SYSTEM_ACTOR,
                "inventory-reservation:" + reservation.reservationKey(),
                null,
                detail + ",skuId=" + reservation.skuId() + ",warehouseId=" + reservation.warehouseId());
    }

    private static String normalizeKey(String key, String name) {
        if (!StringUtils.hasText(key)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, name + " is required");
        }
        return key.trim();
    }

    private static InventoryReservationResponseDto toReservationResponse(
            InventoryReservation reservation, WarehouseStock stock) {
        return InventoryDtoAssembler.toResponse(reservation, stock);
    }
}
