package com.example.monkey.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import com.example.monkey.inventory.application.dto.InventoryCompensateRequestDto;
import com.example.monkey.inventory.application.dto.InventoryReservationResponseDto;
import com.example.monkey.inventory.application.dto.InventoryReserveRequestDto;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class InventoryApplicationServiceTest {

    @Test
    void reservationKeyCannotBeReusedForAnotherSku() {
        InMemoryInventoryStore store = new InMemoryInventoryStore(
                new WarehouseStock(101L, 2200000000001L, "BJ-01", "CN-BJ", 5, 0, 0, 0, 1, 0),
                new WarehouseStock(202L, 2200000000001L, "BJ-01", "CN-BJ", 5, 0, 0, 0, 1, 0));

        assertDifferentPayloadRejected(
                store,
                new InventoryReserveRequestDto(101L, 2200000000001L, null, 10L, 1, "same-key"),
                new InventoryReserveRequestDto(202L, 2200000000001L, null, 10L, 1, "same-key"));
    }

    @Test
    void reservationKeyCannotBeReusedForAnotherRequestedWarehouse() {
        InMemoryInventoryStore store = new InMemoryInventoryStore(
                new WarehouseStock(101L, 2200000000001L, "BJ-01", "CN-BJ", 5, 0, 0, 0, 1, 0),
                new WarehouseStock(101L, 2200000000002L, "SH-01", "CN-SH", 5, 0, 0, 0, 1, 0));

        assertDifferentPayloadRejected(
                store,
                new InventoryReserveRequestDto(101L, 2200000000001L, null, 10L, 1, "same-key"),
                new InventoryReserveRequestDto(101L, 2200000000002L, null, 10L, 1, "same-key"));
    }

    @Test
    void reservationKeyCannotConfuseAnImplicitWarehouseWithAnExplicitWarehouse() {
        InMemoryInventoryStore store = new InMemoryInventoryStore(
                new WarehouseStock(101L, 2200000000001L, "BJ-01", "CN-BJ", 5, 0, 0, 0, 1, 0));

        assertDifferentPayloadRejected(
                store,
                new InventoryReserveRequestDto(101L, null, "CN-BJ", 10L, 1, "same-key"),
                new InventoryReserveRequestDto(101L, 2200000000001L, "CN-BJ", 10L, 1, "same-key"));
    }

    @Test
    void reservationKeyCannotBeReusedForAnotherProvince() {
        InMemoryInventoryStore store = new InMemoryInventoryStore(
                new WarehouseStock(101L, 2200000000001L, "BJ-01", "CN-BJ", 5, 0, 0, 0, 1, 0),
                new WarehouseStock(101L, 2200000000002L, "SH-01", "CN-SH", 5, 0, 0, 0, 1, 0));

        assertDifferentPayloadRejected(
                store,
                new InventoryReserveRequestDto(101L, null, "CN-BJ", 10L, 1, "same-key"),
                new InventoryReserveRequestDto(101L, null, "CN-SH", 10L, 1, "same-key"));
    }

    @Test
    void reservationKeyCannotBeReusedForAnotherOrder() {
        InMemoryInventoryStore store = new InMemoryInventoryStore(
                new WarehouseStock(101L, 2200000000001L, "BJ-01", "CN-BJ", 5, 0, 0, 0, 1, 0));

        assertDifferentPayloadRejected(
                store,
                new InventoryReserveRequestDto(101L, 2200000000001L, null, 10L, 1, "same-key"),
                new InventoryReserveRequestDto(101L, 2200000000001L, null, 11L, 1, "same-key"));
    }

    @Test
    void reservationKeyCannotBeReusedForAnotherQuantity() {
        InMemoryInventoryStore store = new InMemoryInventoryStore(
                new WarehouseStock(101L, 2200000000001L, "BJ-01", "CN-BJ", 5, 0, 0, 0, 1, 0));

        assertDifferentPayloadRejected(
                store,
                new InventoryReserveRequestDto(101L, 2200000000001L, null, 10L, 1, "same-key"),
                new InventoryReserveRequestDto(101L, 2200000000001L, null, 10L, 2, "same-key"));
    }

    @Test
    void equivalentProvinceWhitespaceIsAnExactReplay() {
        InMemoryInventoryStore store = new InMemoryInventoryStore(
                new WarehouseStock(101L, 2200000000001L, "BJ-01", "CN-BJ", 5, 0, 0, 0, 1, 0));
        InventoryApplicationService service = service(store, Duration.ofMinutes(15));

        InventoryReservationResponseDto first =
                service.reserve(new InventoryReserveRequestDto(101L, null, "CN-BJ", 10L, 1, "same-key"));
        InventoryReservationResponseDto replay =
                service.reserve(new InventoryReserveRequestDto(101L, null, "  CN-BJ  ", 10L, 1, "same-key"));

        assertThat(replay).isEqualTo(first);
        assertThat(store.stock(101L, 2200000000001L).availableQuantity()).isEqualTo(4);
        assertThat(store.ledgerCount(InventoryOperation.RESERVE)).isOne();
    }

    @Test
    void onlyTheDatabaseClaimWinnerMutatesStockWhenDifferentLocksRaceOnOneKey() throws Exception {
        InMemoryInventoryStore store = new InMemoryInventoryStore(
                new WarehouseStock(101L, 2200000000001L, "BJ-01", "CN-BJ", 5, 0, 0, 0, 1, 0),
                new WarehouseStock(101L, 2200000000002L, "SH-01", "CN-SH", 5, 0, 0, 0, 1, 0));
        InventoryApplicationService service =
                service(store, new CoordinatedInventoryLockManager(), Duration.ofMinutes(15));
        InventoryReserveRequestDto first =
                new InventoryReserveRequestDto(101L, 2200000000001L, null, 10L, 1, "racing-key");
        InventoryReserveRequestDto second =
                new InventoryReserveRequestDto(101L, 2200000000002L, null, 11L, 2, "racing-key");

        var executor = Executors.newFixedThreadPool(2);
        List<Callable<ReservationAttempt>> tasks =
                List.of(() -> attempt(service, first), () -> attempt(service, second));
        List<ReservationAttempt> attempts = executor.invokeAll(tasks).stream()
                .map(future -> {
                    try {
                        return future.get();
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                })
                .toList();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(attempts).extracting(ReservationAttempt::succeeded).containsExactlyInAnyOrder(true, false);
        int winningQuantity = attempts.stream()
                .filter(ReservationAttempt::succeeded)
                .findFirst()
                .orElseThrow()
                .quantity();
        assertThat(store.stock(101L, 2200000000001L).availableQuantity()
                        + store.stock(101L, 2200000000002L).availableQuantity())
                .isEqualTo(10 - winningQuantity);
        assertThat(store.stock(101L, 2200000000001L).lockedQuantity()
                        + store.stock(101L, 2200000000002L).lockedQuantity())
                .isEqualTo(winningQuantity);
        assertThat(store.ledgerCount(InventoryOperation.RESERVE)).isOne();
    }

    private static void assertDifferentPayloadRejected(
            InMemoryInventoryStore store, InventoryReserveRequestDto first, InventoryReserveRequestDto different) {
        InventoryApplicationService service = service(store, Duration.ofMinutes(15));

        service.reserve(first);

        assertThatThrownBy(() -> service.reserve(different))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::errorCode)
                .isEqualTo(ErrorCode.CONFLICT);
    }

    private static ReservationAttempt attempt(InventoryApplicationService service, InventoryReserveRequestDto request) {
        try {
            InventoryReservationResponseDto response = service.reserve(request);
            return new ReservationAttempt(true, response.quantity());
        } catch (BusinessException exception) {
            assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT);
            return new ReservationAttempt(false, 0);
        }
    }

    @Test
    void oneHundredConcurrentReservationsDoNotOversellTenUnits() throws Exception {
        InMemoryInventoryStore store = new InMemoryInventoryStore(
                new WarehouseStock(101L, 2200000000001L, "BJ-01", "CN-BJ", 10, 0, 0, 0, 1, 0));
        InventoryApplicationService service = service(store, Duration.ofMinutes(15));
        var executor = Executors.newFixedThreadPool(12);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            int index = i;
            tasks.add(() -> {
                try {
                    service.reserve(new InventoryReserveRequestDto(101L, null, "CN-BJ", (long) index, 1, "r-" + index));
                    return true;
                } catch (BusinessException exception) {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.OUT_OF_STOCK);
                    return false;
                }
            });
        }

        long successCount = executor.invokeAll(tasks).stream()
                .filter(future -> {
                    try {
                        return future.get();
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                })
                .count();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        WarehouseStock stock = store.stock(101L, 2200000000001L);
        assertThat(successCount).isEqualTo(10);
        assertThat(stock.availableQuantity()).isZero();
        assertThat(stock.lockedQuantity()).isEqualTo(10);
        assertThat(store.ledgerCount(InventoryOperation.RESERVE)).isEqualTo(10);
    }

    @Test
    void releaseIsIdempotentAndDeductMovesLockedInventory() {
        InMemoryInventoryStore store = new InMemoryInventoryStore(
                new WarehouseStock(101L, 2200000000001L, "BJ-01", "CN-BJ", 5, 0, 0, 0, 1, 0));
        InventoryApplicationService service = service(store, Duration.ofMinutes(15));

        service.reserve(new InventoryReserveRequestDto(101L, 2200000000001L, null, 1L, 2, "release-me"));
        service.release("release-me");
        service.release("release-me");
        service.reserve(new InventoryReserveRequestDto(101L, 2200000000001L, null, 2L, 3, "deduct-me"));
        service.deduct("deduct-me");

        WarehouseStock stock = store.stock(101L, 2200000000001L);
        assertThat(stock.availableQuantity()).isEqualTo(2);
        assertThat(stock.lockedQuantity()).isZero();
        assertThat(stock.deductedQuantity()).isEqualTo(3);
        assertThat(store.reservation("release-me").status()).isEqualTo(InventoryReservationStatus.RELEASED);
        assertThat(store.reservation("deduct-me").status()).isEqualTo(InventoryReservationStatus.DEDUCTED);
    }

    @Test
    void concurrentReleaseRetriesMutateStockExactlyOnceAfterBothReadReserved() throws Exception {
        assertConcurrentLifecycleTransition(false);
    }

    @Test
    void concurrentDeductRetriesMutateStockExactlyOnceAfterBothReadReserved() throws Exception {
        assertConcurrentLifecycleTransition(true);
    }

    private static void assertConcurrentLifecycleTransition(boolean deduct) throws Exception {
        InMemoryInventoryStore store = new InMemoryInventoryStore(
                new WarehouseStock(101L, 2200000000001L, "BJ-01", "CN-BJ", 4, 6, 0, 0, 1, 0));
        LocalDateTime expiresAt = LocalDateTime.of(2026, 7, 4, 0, 15);
        store.saveReservation(new InventoryReservation(
                9001L, "lifecycle-race", 101L, 2200000000001L, 10L, 1, InventoryReservationStatus.RESERVED, expiresAt));
        store.saveReservation(new InventoryReservation(
                9002L,
                "unrelated-reservation",
                101L,
                2200000000001L,
                11L,
                5,
                InventoryReservationStatus.RESERVED,
                expiresAt));

        InventoryApplicationService service =
                service(store, new CoordinatedSerializedInventoryLockManager(), Duration.ofMinutes(15));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<InventoryReservationResponseDto>> tasks = List.of(
                    () -> deduct ? service.deduct("lifecycle-race") : service.release("lifecycle-race"),
                    () -> deduct ? service.deduct("lifecycle-race") : service.release("lifecycle-race"));
            List<InventoryReservationResponseDto> responses = executor.invokeAll(tasks).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();

            InventoryReservationStatus terminalStatus =
                    deduct ? InventoryReservationStatus.DEDUCTED : InventoryReservationStatus.RELEASED;
            InventoryOperation operation = deduct ? InventoryOperation.DEDUCT : InventoryOperation.RELEASE;
            assertThat(responses)
                    .hasSize(2)
                    .allSatisfy(response -> assertThat(response.status()).isEqualTo(terminalStatus));
            assertThat(store.stock(101L, 2200000000001L).availableQuantity()).isEqualTo(deduct ? 4 : 5);
            assertThat(store.stock(101L, 2200000000001L).lockedQuantity()).isEqualTo(5);
            assertThat(store.stock(101L, 2200000000001L).deductedQuantity()).isEqualTo(deduct ? 1 : 0);
            assertThat(store.stockSaveCount()).isEqualTo(1);
            assertThat(store.ledgerCount(operation)).isOne();

            InventoryReservationResponseDto replay =
                    deduct ? service.deduct("lifecycle-race") : service.release("lifecycle-race");
            assertThat(replay.status()).isEqualTo(terminalStatus);
            assertThat(store.stockSaveCount()).isEqualTo(1);
            assertThat(store.ledgerCount(operation)).isOne();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void orderLifecycleDeductsReservationAndCompensatesExactReturnedQuantityIdempotently() {
        InMemoryInventoryStore store = new InMemoryInventoryStore(
                new WarehouseStock(101L, 2200000000001L, "BJ-01", "CN-BJ", 5, 0, 0, 0, 1, 0));
        InventoryApplicationService service = service(store, Duration.ofMinutes(15));
        service.reserve(new InventoryReserveRequestDto(101L, 2200000000001L, null, 10L, 3, "checkout-line-1"));

        service.deductReservation("checkout-line-1");
        service.compensateReturn(101L, 2200000000001L, 99L, 3, "return:99:1");
        service.compensateReturn(101L, 2200000000001L, 99L, 3, "return:99:1");

        WarehouseStock stock = store.stock(101L, 2200000000001L);
        assertThat(stock.availableQuantity()).isEqualTo(5);
        assertThat(stock.lockedQuantity()).isZero();
        assertThat(stock.deductedQuantity()).isZero();
        assertThat(store.reservation("checkout-line-1").status()).isEqualTo(InventoryReservationStatus.DEDUCTED);
        assertThat(store.ledgerCount(InventoryOperation.DEDUCT)).isOne();
        assertThat(store.ledgerCount(InventoryOperation.COMPENSATE)).isOne();
    }

    @Test
    void compensationReplayDoesNotIncreaseStockOrAuditTwice() {
        InMemoryInventoryStore store = new InMemoryInventoryStore(
                new WarehouseStock(101L, 2200000000001L, "BJ-01", "CN-BJ", 5, 0, 4, 0, 1, 0));
        AuditService auditService = mock(AuditService.class);
        InventoryApplicationService service = service(store, auditService, Duration.ofMinutes(15));
        InventoryCompensateRequestDto request =
                new InventoryCompensateRequestDto(101L, 2200000000001L, 1L, 2, "compensate-once");

        service.compensate(request);
        var replay = service.compensate(request);

        WarehouseStock stock = store.stock(101L, 2200000000001L);
        assertThat(stock.availableQuantity()).isEqualTo(7);
        assertThat(stock.deductedQuantity()).isEqualTo(2);
        assertThat(replay.availableQuantity()).isEqualTo(7);
        assertThat(store.ledgerCount(InventoryOperation.COMPENSATE)).isOne();
        assertThat(mockingDetails(auditService).getInvocations()).hasSize(1);
    }

    @Test
    void compensationKeyIsGlobalAcrossStockLocksWithoutSuppressingDistinctAudit() {
        InMemoryInventoryStore store = new InMemoryInventoryStore(
                new WarehouseStock(101L, 2200000000001L, "BJ-01", "CN-BJ", 0, 0, 1, 0, 0, 0),
                new WarehouseStock(202L, 2200000000002L, "SH-01", "CN-SH", 0, 0, 2, 0, 0, 0));
        AuditService auditService = mock(AuditService.class);
        InventoryApplicationService service = service(store, auditService, Duration.ofMinutes(15));

        service.compensate(new InventoryCompensateRequestDto(101L, 2200000000001L, 1L, 1, "shared-compensation-key"));
        service.compensate(new InventoryCompensateRequestDto(202L, 2200000000002L, 2L, 1, "distinct-compensation-key"));
        var duplicate = service.compensate(
                new InventoryCompensateRequestDto(202L, 2200000000002L, 3L, 1, "shared-compensation-key"));

        assertThat(store.stock(101L, 2200000000001L).availableQuantity()).isOne();
        assertThat(store.stock(202L, 2200000000002L).availableQuantity()).isOne();
        assertThat(store.stock(202L, 2200000000002L).deductedQuantity()).isOne();
        assertThat(duplicate.availableQuantity()).isOne();
        assertThat(store.ledgerCount(InventoryOperation.COMPENSATE)).isEqualTo(2);
        assertThat(mockingDetails(auditService).getInvocations()).hasSize(2);
    }

    @Test
    void reconciliationDetectsStockLedgerDrift() {
        InMemoryInventoryStore store = new InMemoryInventoryStore(
                new WarehouseStock(101L, 2200000000001L, "BJ-01", "CN-BJ", 5, 2, 0, 0, 1, 0));
        InventoryApplicationService service = service(store, Duration.ofMinutes(15));

        assertThat(service.reconcile().balanced()).isFalse();
        assertThat(service.reconcile().discrepancies()).hasSize(1);
    }

    @Test
    void expiredReservationsAreReleasedByScheduledJob() {
        InMemoryInventoryStore store = new InMemoryInventoryStore(
                new WarehouseStock(101L, 2200000000001L, "BJ-01", "CN-BJ", 2, 0, 0, 0, 1, 0));
        InventoryApplicationService service = service(store, Duration.ZERO);

        service.reserve(new InventoryReserveRequestDto(101L, 2200000000001L, null, 1L, 1, "expires"));
        int released = service.releaseExpiredReservations();

        WarehouseStock stock = store.stock(101L, 2200000000001L);
        assertThat(released).isEqualTo(1);
        assertThat(stock.availableQuantity()).isEqualTo(2);
        assertThat(stock.lockedQuantity()).isZero();
        assertThat(store.reservation("expires").status()).isEqualTo(InventoryReservationStatus.EXPIRED);
    }

    private static InventoryApplicationService service(InMemoryInventoryStore store, Duration ttl) {
        return service(store, mock(AuditService.class), ttl);
    }

    private static InventoryApplicationService service(
            InMemoryInventoryStore store, InventoryLockManager lockManager, Duration ttl) {
        return new InventoryApplicationService(
                store,
                lockManager,
                new AtomicIdGenerator(),
                mock(AuditService.class),
                Clock.fixed(Instant.parse("2026-07-04T00:00:00Z"), ZoneOffset.UTC),
                ttl);
    }

    private static InventoryApplicationService service(
            InMemoryInventoryStore store, AuditService auditService, Duration ttl) {
        return new InventoryApplicationService(
                store,
                new SynchronizedInventoryLockManager(),
                new AtomicIdGenerator(),
                auditService,
                Clock.fixed(Instant.parse("2026-07-04T00:00:00Z"), ZoneOffset.UTC),
                ttl);
    }

    private static final class AtomicIdGenerator implements IdGenerator {
        private final AtomicLong ids = new AtomicLong(9000);

        @Override
        public long nextId() {
            return ids.incrementAndGet();
        }
    }

    private static final class SynchronizedInventoryLockManager implements InventoryLockManager {
        private final Map<String, Object> locks = new ConcurrentHashMap<>();

        @Override
        public <T> T withStockLock(Long skuId, Long warehouseId, Supplier<T> action) {
            Object lock = locks.computeIfAbsent(skuId + ":" + warehouseId, ignored -> new Object());
            synchronized (lock) {
                return action.get();
            }
        }
    }

    private static final class CoordinatedSerializedInventoryLockManager implements InventoryLockManager {
        private final CyclicBarrier beforeAction = new CyclicBarrier(2);
        private final ReentrantLock actionLock = new ReentrantLock();

        @Override
        public <T> T withStockLock(Long skuId, Long warehouseId, Supplier<T> action) {
            try {
                beforeAction.await(5, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new AssertionError("lifecycle race did not capture both reservations before locking", exception);
            }
            actionLock.lock();
            try {
                return action.get();
            } finally {
                actionLock.unlock();
            }
        }
    }

    private static final class CoordinatedInventoryLockManager implements InventoryLockManager {
        private final CyclicBarrier barrier = new CyclicBarrier(2);

        @Override
        public <T> T withStockLock(Long skuId, Long warehouseId, Supplier<T> action) {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new AssertionError("reservation race did not reach both stock locks", exception);
            }
            return action.get();
        }
    }

    private record ReservationAttempt(boolean succeeded, int quantity) {}

    private static final class InMemoryInventoryStore implements InventoryStore {
        private final Map<String, WarehouseStock> stocks = new ConcurrentHashMap<>();
        private final Map<String, InventoryReservation> reservations = new ConcurrentHashMap<>();
        private final Map<String, InventoryStockLedgerEntry> ledgers = new ConcurrentHashMap<>();
        private final AtomicLong stockSaves = new AtomicLong();

        private InMemoryInventoryStore(WarehouseStock... seedStocks) {
            for (WarehouseStock stock : seedStocks) {
                stocks.put(key(stock.skuId(), stock.warehouseId()), stock);
            }
        }

        @Override
        public Optional<WarehouseStock> findStock(Long skuId, Long warehouseId) {
            return Optional.ofNullable(stocks.get(key(skuId, warehouseId)));
        }

        @Override
        public List<WarehouseStock> findStocksBySku(Long skuId) {
            return stocks.values().stream()
                    .filter(stock -> stock.skuId().equals(skuId))
                    .toList();
        }

        @Override
        public WarehouseStock saveStock(WarehouseStock stock) {
            stockSaves.incrementAndGet();
            stocks.put(key(stock.skuId(), stock.warehouseId()), stock);
            return stock;
        }

        @Override
        public Optional<InventoryReservation> findReservation(String reservationKey) {
            return Optional.ofNullable(reservations.get(reservationKey));
        }

        @Override
        public boolean saveReservationIfAbsent(InventoryReservation reservation) {
            return reservations.putIfAbsent(reservation.reservationKey(), reservation) == null;
        }

        @Override
        public InventoryReservation saveReservation(InventoryReservation reservation) {
            reservations.put(reservation.reservationKey(), reservation);
            return reservation;
        }

        @Override
        public boolean recordLedger(InventoryStockLedgerEntry ledgerEntry) {
            return ledgers.putIfAbsent(ledgerEntry.idempotencyKey(), ledgerEntry) == null;
        }

        @Override
        public List<InventoryReservation> findExpiredReservations(LocalDateTime now, int limit) {
            return reservations.values().stream()
                    .filter(reservation -> reservation.expiredAt(now))
                    .limit(limit)
                    .toList();
        }

        @Override
        public InventoryReconciliationReport reconcile() {
            Map<String, int[]> balances = new ConcurrentHashMap<>();
            for (InventoryStockLedgerEntry ledger : ledgers.values()) {
                int[] balance =
                        balances.computeIfAbsent(key(ledger.skuId(), ledger.warehouseId()), ignored -> new int[2]);
                switch (ledger.operation()) {
                    case RESERVE -> balance[0] += ledger.quantity();
                    case RELEASE -> balance[0] -= ledger.quantity();
                    case DEDUCT -> {
                        balance[0] -= ledger.quantity();
                        balance[1] += ledger.quantity();
                    }
                    case COMPENSATE -> balance[1] -= ledger.quantity();
                }
            }
            List<InventoryReconciliationReport.Discrepancy> discrepancies = stocks.values().stream()
                    .flatMap(stock -> {
                        int[] balance = balances.getOrDefault(key(stock.skuId(), stock.warehouseId()), new int[2]);
                        if (stock.lockedQuantity() == balance[0] && stock.deductedQuantity() == balance[1]) {
                            return java.util.stream.Stream.empty();
                        }
                        return java.util.stream.Stream.of(new InventoryReconciliationReport.Discrepancy(
                                stock.skuId(),
                                stock.warehouseId(),
                                stock.lockedQuantity(),
                                balance[0],
                                stock.deductedQuantity(),
                                balance[1]));
                    })
                    .toList();
            return new InventoryReconciliationReport(discrepancies);
        }

        private WarehouseStock stock(Long skuId, Long warehouseId) {
            return stocks.get(key(skuId, warehouseId));
        }

        private InventoryReservation reservation(String reservationKey) {
            return reservations.get(reservationKey);
        }

        private long ledgerCount(InventoryOperation operation) {
            return ledgers.values().stream()
                    .filter(ledger -> ledger.operation() == operation)
                    .count();
        }

        private long stockSaveCount() {
            return stockSaves.get();
        }

        private static String key(Long skuId, Long warehouseId) {
            return skuId + ":" + warehouseId;
        }
    }
}
