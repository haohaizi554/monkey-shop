package com.example.monkey.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.cart.application.CartApplicationService;
import com.example.monkey.cart.application.CartCleanupProcessor;
import com.example.monkey.cart.application.CartCleanupRetryWorker;
import com.example.monkey.cart.application.DurableCartCleanupScheduler;
import com.example.monkey.cart.application.dto.CartCheckoutRequestDto;
import com.example.monkey.cart.domain.CartCleanupIntent;
import com.example.monkey.cart.domain.CartCleanupIntentStatus;
import com.example.monkey.cart.domain.CartCleanupIntentStore;
import com.example.monkey.cart.domain.CartCleanupTenantSource;
import com.example.monkey.cart.domain.CartItem;
import com.example.monkey.cart.domain.CartLockManager;
import com.example.monkey.cart.domain.CartSkuSnapshot;
import com.example.monkey.cart.domain.CartSnapshot;
import com.example.monkey.cart.domain.CartStore;
import com.example.monkey.cart.domain.CheckoutOrder;
import com.example.monkey.cart.infrastructure.CartCheckoutLineRepository;
import com.example.monkey.cart.infrastructure.CartCheckoutRepository;
import com.example.monkey.cart.infrastructure.CartCleanupIntentRepository;
import com.example.monkey.cart.infrastructure.CartSubOrderRepository;
import com.example.monkey.cart.infrastructure.JdbcCartCleanupTenantSource;
import com.example.monkey.cart.infrastructure.JpaCartCheckoutStore;
import com.example.monkey.cart.infrastructure.JpaCartCleanupIntentStore;
import com.example.monkey.cart.infrastructure.RedisCartStore;
import com.example.monkey.cart.infrastructure.RequiresNewCartTransactions;
import com.example.monkey.inventory.application.InventoryApplicationService;
import com.example.monkey.inventory.application.dto.InventoryReservationResponseDto;
import com.example.monkey.inventory.application.dto.InventoryReserveRequestDto;
import com.example.monkey.inventory.application.dto.WarehouseStockResponseDto;
import com.example.monkey.inventory.domain.InventoryLockManager;
import com.example.monkey.inventory.domain.InventoryReservationStatus;
import com.example.monkey.inventory.infrastructure.InventoryReservationRepository;
import com.example.monkey.inventory.infrastructure.InventoryStockLedgerRepository;
import com.example.monkey.inventory.infrastructure.InventoryStockRepository;
import com.example.monkey.inventory.infrastructure.InventoryWarehouseRepository;
import com.example.monkey.inventory.infrastructure.JpaInventoryStore;
import com.example.monkey.marketing.application.MarketingApplicationService;
import com.example.monkey.marketing.application.dto.MarketingPriceQuoteDto;
import com.example.monkey.marketing.application.dto.MarketingPriceRequestDto;
import com.example.monkey.order.application.CheckoutOrderApplicationService;
import com.example.monkey.order.domain.OrderCustomerPort;
import com.example.monkey.order.domain.OrderNumberGenerator;
import com.example.monkey.order.domain.OrderStore.AddressRecord;
import com.example.monkey.order.domain.OrderStore.BuyerRecord;
import com.example.monkey.order.infrastructure.JpaOrderStore;
import com.example.monkey.order.infrastructure.OrderFormalOrderCreator;
import com.example.monkey.order.infrastructure.OrderLineRepository;
import com.example.monkey.order.infrastructure.OrderRepository;
import com.example.monkey.order.infrastructure.StockLogRepository;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@MockitoBean(types = PiiCryptoService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CheckoutTransactionIntegrationTest {

    private static final SessionUser USER = new SessionUser(7L, "USER");

    private final CartCheckoutRepository checkoutRepository;
    private final CartCleanupIntentRepository cleanupIntentRepository;
    private final CartSubOrderRepository subOrderRepository;
    private final CartCheckoutLineRepository lineRepository;
    private final OrderRepository orderRepository;
    private final OrderLineRepository orderLineRepository;
    private final StockLogRepository stockLogRepository;
    private final PlatformTransactionManager transactionManager;
    private final InventoryReservationRepository reservationRepository;
    private final InventoryStockRepository inventoryStockRepository;
    private final InventoryWarehouseRepository warehouseRepository;
    private final InventoryStockLedgerRepository inventoryLedgerRepository;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    CheckoutTransactionIntegrationTest(
            CartCheckoutRepository checkoutRepository,
            CartCleanupIntentRepository cleanupIntentRepository,
            CartSubOrderRepository subOrderRepository,
            CartCheckoutLineRepository lineRepository,
            OrderRepository orderRepository,
            OrderLineRepository orderLineRepository,
            StockLogRepository stockLogRepository,
            PlatformTransactionManager transactionManager,
            InventoryReservationRepository reservationRepository,
            InventoryStockRepository inventoryStockRepository,
            InventoryWarehouseRepository warehouseRepository,
            InventoryStockLedgerRepository inventoryLedgerRepository,
            JdbcTemplate jdbcTemplate) {
        this.checkoutRepository = checkoutRepository;
        this.cleanupIntentRepository = cleanupIntentRepository;
        this.subOrderRepository = subOrderRepository;
        this.lineRepository = lineRepository;
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.stockLogRepository = stockLogRepository;
        this.transactionManager = transactionManager;
        this.reservationRepository = reservationRepository;
        this.inventoryStockRepository = inventoryStockRepository;
        this.warehouseRepository = warehouseRepository;
        this.inventoryLedgerRepository = inventoryLedgerRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void clearCommittedFixtureRows() {
        jdbcTemplate.update("DELETE FROM cart_cleanup_intent");
        jdbcTemplate.update("DELETE FROM order_line");
        jdbcTemplate.update("DELETE FROM cart_checkout_line");
        jdbcTemplate.update("DELETE FROM cart_sub_order");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM cart_checkout");
        jdbcTemplate.update("DELETE FROM inventory_stock_ledger");
        jdbcTemplate.update("DELETE FROM inventory_reservation");
        jdbcTemplate.update("DELETE FROM inventory_stock");
        jdbcTemplate.update("DELETE FROM inventory_warehouse");
    }

    @Test
    void checkoutPersistsPendingOrdersBeforeClearingSelectedCartLines() {
        Fixture fixture = new Fixture(
                new JpaCartCheckoutStore(checkoutRepository, subOrderRepository, lineRepository),
                new OrderFormalOrderCreator(new CheckoutOrderApplicationService(
                        new JpaOrderStore(orderRepository, stockLogRepository, orderLineRepository), customerPort())),
                cleanupIntentRepository,
                new JdbcCartCleanupTenantSource(jdbcTemplate),
                transactionManager);
        fixture.seedSelectedCart();

        var checkout =
                fixture.service.checkout(USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of()), "checkout-key-1");
        assertThat(checkoutRepository.findById(checkout.id())).isPresent();
        var persistedSubOrders = subOrderRepository.findByCheckoutIdOrderByIdAsc(checkout.id());
        var persistedCheckoutLines = lineRepository.findByCheckoutIdOrderBySubOrderIdAscIdAsc(checkout.id());
        assertThat(persistedSubOrders).hasSize(2);
        assertThat(persistedCheckoutLines).hasSize(2);
        assertThat(fixture.cartStore.findCart(USER.id()).items()).isEmpty();
        ArgumentCaptor<InventoryReserveRequestDto> reservations =
                ArgumentCaptor.forClass(InventoryReserveRequestDto.class);
        verify(fixture.inventoryApplicationService, times(2)).reserve(reservations.capture());

        var orders = orderRepository.findByCheckoutIdOrderByCheckoutSubOrderIdAsc(checkout.id());
        assertThat(orders).hasSize(2).allSatisfy(order -> {
            assertThat(order.getCheckoutId()).isEqualTo(checkout.id());
            assertThat(order.getUserId()).isEqualTo(USER.id());
            assertThat(order.getStatus()).isIn("PENDING_PAYMENT", "\u5f85\u652f\u4ed8");
            assertThat(order.getCheckoutIdempotencyKey()).isEqualTo("checkout-key-1");
        });
        assertThat(orders.stream().map(order -> order.getCheckoutSubOrderId()).toList())
                .containsExactlyInAnyOrderElementsOf(checkout.subOrders().stream()
                        .map(subOrder -> subOrder.id())
                        .toList());

        for (var subOrder : checkout.subOrders()) {
            var order = orders.stream()
                    .filter(candidate -> candidate.getCheckoutSubOrderId().equals(subOrder.id()))
                    .findFirst()
                    .orElseThrow();
            assertThat(order.getId()).isEqualTo(subOrder.formalOrderId());
            assertThat(order.getShopId()).isEqualTo(subOrder.shopId());
            assertThat(order.getOriginalAmount()).isEqualByComparingTo(subOrder.originalAmount());
            assertThat(order.getDiscountAmount()).isEqualByComparingTo(subOrder.discountAmount());
            assertThat(order.getPrice()).isEqualByComparingTo(subOrder.payableAmount());

            var orderLines = orderLineRepository.findByOrderIdOrderByIdAsc(order.getId());
            assertThat(orderLines).hasSameSizeAs(subOrder.lines());
            for (var checkoutLine : subOrder.lines()) {
                var orderLine = orderLines.stream()
                        .filter(candidate -> candidate.getCheckoutLineId().equals(checkoutLine.id()))
                        .findFirst()
                        .orElseThrow();
                assertThat(orderLine.getSkuId()).isEqualTo(checkoutLine.skuId());
                assertThat(orderLine.getShopId()).isEqualTo(checkoutLine.shopId());
                assertThat(orderLine.getCategoryId()).isEqualTo(checkoutLine.categoryId());
                assertThat(orderLine.getProductName()).isEqualTo(checkoutLine.productName());
                assertThat(orderLine.getProductImage()).isEqualTo(checkoutLine.productImage());
                assertThat(orderLine.getQuantity()).isEqualTo(checkoutLine.quantity());
                assertThat(orderLine.getUnitPrice()).isEqualByComparingTo(checkoutLine.unitPrice());
                assertThat(orderLine.getOriginalAmount()).isEqualByComparingTo(checkoutLine.originalAmount());
                assertThat(orderLine.getDiscountAmount()).isEqualByComparingTo(checkoutLine.discountAmount());
                assertThat(orderLine.getPayableAmount()).isEqualByComparingTo(checkoutLine.payableAmount());
                assertThat(orderLine.getCouponCodes()).isEqualTo(String.join(",", checkoutLine.couponCodes()));
                assertThat(orderLine.getReservationKey()).isEqualTo(checkoutLine.reservationKey());
                assertThat(orderLine.getWarehouseId()).isEqualTo(checkoutLine.warehouseId());
            }
        }

        assertThat(checkout.subOrders().stream()
                        .map(subOrder -> subOrder.originalAmount())
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(checkout.originalAmount());
        assertThat(checkout.subOrders().stream()
                        .map(subOrder -> subOrder.storeDiscountAmount().add(subOrder.platformDiscountAmount()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(checkout.discountAmount());
        assertThat(checkout.subOrders().stream()
                        .map(subOrder -> subOrder.payableAmount())
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(checkout.payableAmount());
        assertThat(reservations.getAllValues()).allSatisfy(reservation -> {
            assertThat(reservation.orderId()).isEqualTo(checkout.id());
            assertThat(checkout.subOrders().stream()
                            .flatMap(subOrder -> subOrder.lines().stream())
                            .anyMatch(line -> line.reservationKey().equals(reservation.reservationKey())
                                    && line.skuId().equals(reservation.skuId())
                                    && line.quantity() == reservation.quantity()))
                    .isTrue();
        });
    }

    @Test
    void v51LegacyCheckoutReplayReturnsPersistedResponseWithoutNewSideEffects() {
        Fixture fixture = new Fixture(
                new JpaCartCheckoutStore(checkoutRepository, subOrderRepository, lineRepository),
                new OrderFormalOrderCreator(new CheckoutOrderApplicationService(
                        new JpaOrderStore(orderRepository, stockLogRepository, orderLineRepository), customerPort())),
                cleanupIntentRepository,
                new JdbcCartCleanupTenantSource(jdbcTemplate),
                transactionManager);
        fixture.seedSelectedCart();
        var first = fixture.service.checkout(
                USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of()), "v51-legacy-checkout-key");
        var persisted = fixture.service.checkout(
                USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of()), "v51-legacy-checkout-key");
        inNewTransaction(() -> {
            var legacy = checkoutRepository.findById(first.id()).orElseThrow();
            legacy.setRequestFingerprint(CheckoutOrder.LEGACY_V51_REQUEST_FINGERPRINT);
            checkoutRepository.saveAndFlush(legacy);
            return null;
        });
        fixture.seedSelectedCart();

        var replay = fixture.service.checkout(
                USER, new CartCheckoutRequestDto(9L, "CN-SH", List.of("DIFFERENT")), "v51-legacy-checkout-key");

        assertThat(replay).isEqualTo(persisted);
        assertThat(checkoutRepository.count()).isEqualTo(1L);
        assertThat(orderRepository.count()).isEqualTo(2L);
        assertThat(cleanupIntentRepository.count()).isEqualTo(1L);
        verify(fixture.inventoryApplicationService, times(2)).reserve(any());
        verify(fixture.marketingApplicationService, times(1)).redeemForCheckout(any(), any(), any());
    }

    @Test
    void failureAfterFormalOrderCreationRollsBackDatabaseAndKeepsCart() {
        Fixture fixture = new Fixture(
                new JpaCartCheckoutStore(checkoutRepository, subOrderRepository, lineRepository),
                new OrderFormalOrderCreator(new CheckoutOrderApplicationService(
                        new JpaOrderStore(orderRepository, stockLogRepository, orderLineRepository), customerPort())),
                cleanupIntentRepository,
                new JdbcCartCleanupTenantSource(jdbcTemplate),
                transactionManager);
        fixture.seedSelectedCart();
        fixture.failAuditAfterCartCleanup();

        assertThatThrownBy(() -> fixture.service.checkout(
                        USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of()), "checkout-key-rollback"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");

        assertThat(checkoutRepository.count()).isZero();
        assertThat(subOrderRepository.count()).isZero();
        assertThat(lineRepository.count()).isZero();
        assertThat(orderRepository.count()).isZero();
        assertThat(orderLineRepository.count()).isZero();
        assertThat(fixture.cartStore.findCart(USER.id()).items()).hasSize(2);
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void sameKeyLockIsHeldThroughCommitBeforeConflictingReplayRuns() throws Exception {
        ObservingCartLockManager lockManager = new ObservingCartLockManager();
        Fixture fixture = new Fixture(
                new JpaCartCheckoutStore(checkoutRepository, subOrderRepository, lineRepository),
                new OrderFormalOrderCreator(new CheckoutOrderApplicationService(
                        new JpaOrderStore(orderRepository, stockLogRepository, orderLineRepository), customerPort())),
                cleanupIntentRepository,
                new JdbcCartCleanupTenantSource(jdbcTemplate),
                transactionManager,
                mock(InventoryApplicationService.class),
                lockManager);
        fixture.seedSelectedCart();
        CountDownLatch beforeCommit = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        fixture.pauseNextCommit(beforeCommit, releaseCommit);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> fixture.service.checkout(
                    USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of()), "checkout-lock-key"));
            assertThat(beforeCommit.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> second = executor.submit(() -> {
                try {
                    fixture.service.checkout(
                            USER, new CartCheckoutRequestDto(9L, "CN-SH", List.of()), "checkout-lock-key");
                    return null;
                } catch (Throwable throwable) {
                    return throwable;
                }
            });

            boolean secondEnteredBeforeCommit = lockManager.awaitSecondEntry(500, TimeUnit.MILLISECONDS);
            releaseCommit.countDown();
            first.get(5, TimeUnit.SECONDS);
            Throwable conflict = second.get(5, TimeUnit.SECONDS);

            assertThat(secondEnteredBeforeCommit).isFalse();
            assertThat(conflict)
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            verify(fixture.inventoryApplicationService, times(2)).reserve(any());
            verify(fixture.marketingApplicationService, times(1)).redeemForCheckout(any(), any(), any());
            assertThat(checkoutRepository.count()).isEqualTo(1);
            assertThat(orderRepository.count()).isEqualTo(2);
        } finally {
            releaseCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void durableCleanupRetriesAfterPostCommitCartFailure() {
        Fixture fixture = new Fixture(
                new JpaCartCheckoutStore(checkoutRepository, subOrderRepository, lineRepository),
                new OrderFormalOrderCreator(new CheckoutOrderApplicationService(
                        new JpaOrderStore(orderRepository, stockLogRepository, orderLineRepository), customerPort())),
                cleanupIntentRepository,
                new JdbcCartCleanupTenantSource(jdbcTemplate),
                transactionManager);
        fixture.seedSelectedCart();
        fixture.cartStore.failNextCleanup();

        var checkout = fixture.service.checkout(
                USER, new CartCheckoutRequestDto(9L, "CN-BJ", List.of()), "checkout-key-cleanup-retry");

        var pending = cleanupIntentRepository.findById(checkout.id()).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(com.example.monkey.cart.domain.CartCleanupIntentStatus.PENDING);
        assertThat(pending.getAttemptCount()).isEqualTo(1);
        assertThat(pending.getLastError()).contains("cart unavailable");
        assertThat(fixture.cartStore.findCart(USER.id()).items()).hasSize(2);

        pending.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        cleanupIntentRepository.saveAndFlush(pending);
        fixture.cleanupRetryWorker.retryPending();
        var completed = cleanupIntentRepository.findById(checkout.id()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(com.example.monkey.cart.domain.CartCleanupIntentStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(fixture.cartStore.findCart(USER.id()).items()).isEmpty();
        assertThat(fixture.cleanupProcessor.process(checkout.id())).isTrue();
        assertThat(fixture.cartStore.cleanupAttempts()).isEqualTo(2);
    }

    @Test
    void cleanupIntentRoundTripsExactSelectedCartItemSnapshots() {
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:00");
        CartItem selected = new CartItem(1001L, 501L, 2, true, now.minusMinutes(2), now);
        JpaCartCleanupIntentStore store = new JpaCartCleanupIntentStore(
                cleanupIntentRepository, new ObjectMapper().registerModule(new JavaTimeModule()));

        store.save(CartCleanupIntent.pending(8801L, USER.id(), List.of(selected), Duration.ofDays(7), now));

        assertThat(store.findByCheckoutId(8801L).orElseThrow().itemSnapshots()).containsExactly(selected);
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void concurrentCleanupWorkersAllowOnlyOneActiveClaim() throws Exception {
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:00");
        JpaCartCleanupIntentStore store = cleanupIntentStore();
        store.save(CartCleanupIntent.pending(
                8802L, USER.id(), List.of(new CartItem(1001L, 501L, 2, true, now, now)), Duration.ofDays(7), now));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(
                    () -> claimAfterBarrier(store, 8802L, "claim-one", now, now.plusMinutes(1), ready, start));
            Future<Boolean> second = executor.submit(
                    () -> claimAfterBarrier(store, 8802L, "claim-two", now, now.plusMinutes(1), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void concurrentCleanupProcessorsPerformOnlyOneCartCleanup() throws Exception {
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:00");
        CartItem selected = new CartItem(1001L, 501L, 2, true, now, now);
        JpaCartCleanupIntentStore store = cleanupIntentStore();
        store.save(CartCleanupIntent.pending(8806L, USER.id(), List.of(selected), Duration.ofDays(7), now));
        InMemoryCartStore cartStore = new InMemoryCartStore();
        cartStore.seed(new CartSnapshot(USER.id(), List.of(selected)));
        CartCleanupProcessor processor = new CartCleanupProcessor(
                store,
                cartStore,
                transactionManager,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(30));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> processAfterBarrier(processor, 8806L, ready, start));
            Future<Boolean> second = executor.submit(() -> processAfterBarrier(processor, 8806L, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            assertThat(cartStore.cleanupAttempts()).isEqualTo(1);
            assertThat(store.findByCheckoutId(8806L).orElseThrow().status())
                    .isEqualTo(CartCleanupIntentStatus.COMPLETED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredCleanupClaimIsReclaimedAndStaleTokenCannotCompleteOrFail() {
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:00");
        JpaCartCleanupIntentStore store = cleanupIntentStore();
        store.save(CartCleanupIntent.pending(
                8803L, USER.id(), List.of(new CartItem(1001L, 501L, 2, true, now, now)), Duration.ofDays(7), now));

        assertThat(inNewTransaction(() -> store.claim(8803L, "stale-token", now, now.plusSeconds(30))))
                .isPresent();
        LocalDateTime reclaimedAt = now.plusSeconds(31);
        assertThat(inNewTransaction(
                        () -> store.claim(8803L, "current-token", reclaimedAt, reclaimedAt.plusSeconds(30))))
                .isPresent();

        assertThat(inNewTransaction(() -> store.completeClaim(8803L, "stale-token", reclaimedAt)))
                .isFalse();
        assertThat(inNewTransaction(() -> store.failClaim(
                        8803L, "stale-token", reclaimedAt, reclaimedAt.plusMinutes(1), "stale failure")))
                .isFalse();
        assertThat(store.findByCheckoutId(8803L).orElseThrow().claimToken()).isEqualTo("current-token");
        assertThat(inNewTransaction(() -> store.completeClaim(8803L, "current-token", reclaimedAt)))
                .isTrue();
    }

    @Test
    void cleanupReclaimsAfterDeleteBeforeCompletionAndPreservesNewerCartItems() {
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:00");
        LocalDateTime later = now.plusMinutes(1);
        List<CartItem> checkoutSnapshots = List.of(
                new CartItem(1001L, 501L, 2, true, now, now),
                new CartItem(1002L, 501L, 1, true, now, now),
                new CartItem(1003L, 501L, 1, true, now, now),
                new CartItem(1004L, 501L, 1, true, now, now));
        JpaCartCleanupIntentStore delegate = cleanupIntentStore();
        delegate.save(CartCleanupIntent.pending(8804L, USER.id(), checkoutSnapshots, Duration.ofDays(7), now));
        InMemoryCartStore cartStore = new InMemoryCartStore();
        cartStore.seed(new CartSnapshot(USER.id(), checkoutSnapshots));
        CartCleanupIntentStore failFirstCompletion = new FailFirstCompletionIntentStore(delegate);
        CartCleanupProcessor firstProcessor = new CartCleanupProcessor(
                failFirstCompletion,
                cartStore,
                transactionManager,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(30));

        assertThatThrownBy(() -> firstProcessor.process(8804L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("completion unavailable");
        assertThat(delegate.findByCheckoutId(8804L).orElseThrow().status())
                .isEqualTo(CartCleanupIntentStatus.PROCESSING);
        assertThat(cartStore.findCart(USER.id()).items()).isEmpty();
        assertThat(cartStore.cleanupRanInsideTransaction()).isFalse();

        CartItem quantityChanged = new CartItem(1001L, 501L, 3, true, now, later);
        CartItem unselected = new CartItem(1002L, 501L, 1, false, now, later);
        CartItem readded = new CartItem(1003L, 501L, 1, true, later, later);
        CartItem brandNew = new CartItem(2001L, 502L, 1, true, later, later);
        cartStore.seed(new CartSnapshot(USER.id(), List.of(quantityChanged, unselected, readded, brandNew)));
        CartCleanupProcessor retryProcessor = new CartCleanupProcessor(
                delegate,
                cartStore,
                transactionManager,
                Clock.fixed(Instant.parse("2026-01-01T00:00:31Z"), ZoneOffset.UTC),
                Duration.ofSeconds(30));

        assertThat(retryProcessor.process(8804L)).isTrue();
        assertThat(cartStore.findCart(USER.id()).items())
                .containsExactlyInAnyOrder(quantityChanged, unselected, readded, brandNew);
        assertThat(delegate.findByCheckoutId(8804L).orElseThrow().status())
                .isEqualTo(CartCleanupIntentStatus.COMPLETED);
        assertThat(cartStore.cleanupAttempts()).isEqualTo(2);
    }

    @Test
    void retryWorkerProcessesReadyAndExpiredClaimsForTwoTenantsAndRestoresContext() {
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:00");
        JpaCartCleanupIntentStore store = cleanupIntentStore();
        LocalDateTime cutoff = now.plusSeconds(31);
        CartItem tenantOneItem = new CartItem(1001L, 501L, 2, true, now, now);
        CartItem tenantTwoItem = new CartItem(2001L, 502L, 1, true, now, now);
        jdbcTemplate.update("""
                MERGE INTO tenant (id, code, name, status, plan, created_at, expires_at, version) KEY (id)
                VALUES (1, 'cart-worker-one', 'Cart Worker One', 'ACTIVE', 'STARTER', ?, ?, 0)
                """, now, now.plusYears(10));
        jdbcTemplate.update("""
                MERGE INTO tenant (id, code, name, status, plan, created_at, expires_at, version) KEY (id)
                VALUES (2, 'cart-worker-two', 'Cart Worker Two', 'ACTIVE', 'STARTER', ?, ?, 0)
                """, now, now.plusYears(10));
        ObjectProvider<StringRedisTemplate> redisProvider = mock();
        when(redisProvider.getIfAvailable()).thenReturn(null);
        RedisCartStore cartStore = new RedisCartStore(redisProvider, new ObjectMapper().findAndRegisterModules());
        try {
            TenantContext.setTenantId(1L);
            store.save(CartCleanupIntent.pending(8805L, USER.id(), List.of(tenantOneItem), Duration.ofDays(7), now));
            cartStore.putItem(USER.id(), tenantOneItem, Duration.ofDays(7));
            TenantContext.setTenantId(2L);
            store.save(CartCleanupIntent.pending(8807L, USER.id(), List.of(tenantTwoItem), Duration.ofDays(7), now));
            cartStore.putItem(USER.id(), tenantTwoItem, Duration.ofDays(7));
            assertThat(inNewTransaction(() -> store.claim(8807L, "expired-token", now, now.plusSeconds(30))))
                    .isPresent();

            TenantContext.setTenantId(1L);
            assertThat(cartStore.findCart(USER.id()).items()).containsExactly(tenantOneItem);
            TenantContext.setTenantId(2L);
            assertThat(cartStore.findCart(USER.id()).items()).containsExactly(tenantTwoItem);
            JdbcCartCleanupTenantSource tenantSource = new JdbcCartCleanupTenantSource(jdbcTemplate);
            assertThat(tenantSource.findTenantIdsWithReadyIntents(cutoff, 0L, 100))
                    .containsExactly(1L, 2L);
            CartCleanupProcessor processor = new CartCleanupProcessor(
                    store,
                    cartStore,
                    transactionManager,
                    Clock.fixed(Instant.parse("2026-01-01T00:00:31Z"), ZoneOffset.UTC),
                    Duration.ofSeconds(30));
            CartCleanupRetryWorker worker = new CartCleanupRetryWorker(store, tenantSource, processor);
            TenantContext.setTenantId(77L);

            worker.retryPending();

            assertThat(TenantContext.currentTenantId()).contains(77L);
            TenantContext.setTenantId(1L);
            assertThat(store.findByCheckoutId(8805L).orElseThrow().status())
                    .isEqualTo(CartCleanupIntentStatus.COMPLETED);
            assertThat(cartStore.findCart(USER.id()).items()).isEmpty();
            TenantContext.setTenantId(2L);
            assertThat(store.findByCheckoutId(8807L).orElseThrow().status())
                    .isEqualTo(CartCleanupIntentStatus.COMPLETED);
            assertThat(cartStore.findCart(USER.id()).items()).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @EnabledIfEnvironmentVariable(named = "RUN_CART_REDIS_ACCEPTANCE", matches = "true")
    void realRedisCrashWindowReclaimsIntentWithoutDeletingPostCheckoutMutations() {
        String host = System.getenv().getOrDefault("CART_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("CART_REDIS_PORT", "6379"));
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        JedisConnectionFactory connectionFactory = new JedisConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        ObjectProvider<StringRedisTemplate> provider = mock();
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        RedisCartStore cartStore = new RedisCartStore(provider, new ObjectMapper().findAndRegisterModules());
        Long userId = positiveRandomId();
        Long checkoutId = positiveRandomId();
        String key = "cart:tenant:1:user:" + userId;
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:00");
        LocalDateTime later = now.plusMinutes(1);
        List<CartItem> checkoutSnapshots = List.of(
                new CartItem(1001L, 501L, 2, true, now, now),
                new CartItem(1002L, 501L, 1, true, now, now),
                new CartItem(1003L, 501L, 1, true, now, now),
                new CartItem(1004L, 501L, 1, true, now, now));
        JpaCartCleanupIntentStore delegate = cleanupIntentStore();
        try {
            TenantContext.setTenantId(1L);
            delegate.save(CartCleanupIntent.pending(checkoutId, userId, checkoutSnapshots, Duration.ofDays(7), now));
            checkoutSnapshots.forEach(item -> cartStore.putItem(userId, item, Duration.ofDays(7)));
            CartCleanupProcessor firstProcessor = new CartCleanupProcessor(
                    new FailFirstCompletionIntentStore(delegate),
                    cartStore,
                    transactionManager,
                    Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                    Duration.ofSeconds(30));

            assertThatThrownBy(() -> firstProcessor.process(checkoutId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("completion unavailable");
            assertThat(cartStore.findCart(userId).items()).isEmpty();
            assertThat(delegate.findByCheckoutId(checkoutId).orElseThrow().status())
                    .isEqualTo(CartCleanupIntentStatus.PROCESSING);

            CartItem quantityChanged = new CartItem(1001L, 501L, 3, true, now, later);
            CartItem unselected = new CartItem(1002L, 501L, 1, false, now, later);
            CartItem readded = new CartItem(1003L, 501L, 1, true, later, later);
            CartItem brandNew = new CartItem(2001L, 502L, 1, true, later, later);
            List<CartItem> postCheckoutItems = List.of(quantityChanged, unselected, readded, brandNew);
            postCheckoutItems.forEach(item -> cartStore.putItem(userId, item, Duration.ofDays(7)));
            CartCleanupProcessor retryProcessor = new CartCleanupProcessor(
                    delegate,
                    cartStore,
                    transactionManager,
                    Clock.fixed(Instant.parse("2026-01-01T00:00:31Z"), ZoneOffset.UTC),
                    Duration.ofSeconds(30));

            assertThat(retryProcessor.process(checkoutId)).isTrue();
            assertThat(cartStore.findCart(userId).items()).containsExactlyInAnyOrderElementsOf(postCheckoutItems);
            assertThat(delegate.findByCheckoutId(checkoutId).orElseThrow().status())
                    .isEqualTo(CartCleanupIntentStatus.COMPLETED);
        } finally {
            TenantContext.clear();
            redisTemplate.delete(key);
            jdbcTemplate.update("DELETE FROM cart_cleanup_intent WHERE checkout_id = ?", checkoutId);
            connectionFactory.destroy();
        }
    }

    @Test
    void previewIsStrictlyReadOnlyAcrossCommerceState() {
        Fixture fixture = new Fixture(
                new JpaCartCheckoutStore(checkoutRepository, subOrderRepository, lineRepository),
                new OrderFormalOrderCreator(new CheckoutOrderApplicationService(
                        new JpaOrderStore(orderRepository, stockLogRepository, orderLineRepository), customerPort())),
                cleanupIntentRepository,
                new JdbcCartCleanupTenantSource(jdbcTemplate),
                transactionManager);
        fixture.seedSelectedCart();
        CartSnapshot before = fixture.cartStore.findCart(USER.id());

        var preview = fixture.service.previewCheckout(
                USER, new CartCheckoutRequestDto(9L, " cn-bj ", List.of("UNUSED", "UNUSED")));

        assertThat(preview.status()).isEqualTo(com.example.monkey.cart.domain.CartCheckoutStatus.RESERVED);
        assertThat(checkoutRepository.count()).isZero();
        assertThat(subOrderRepository.count()).isZero();
        assertThat(lineRepository.count()).isZero();
        assertThat(orderRepository.count()).isZero();
        assertThat(orderLineRepository.count()).isZero();
        assertThat(cleanupIntentRepository.count()).isZero();
        verify(fixture.inventoryApplicationService, never()).reserve(any(InventoryReserveRequestDto.class));
        verify(fixture.marketingApplicationService, never()).redeemForCheckout(any(), any(), any());
        assertThat(fixture.cartStore.findCart(USER.id())).isEqualTo(before);
    }

    @Test
    void couponFailureAfterFormalOrdersRollsBackCheckoutOrdersAndReservations() {
        seedInventory();
        OrderFormalOrderCreator formalOrderCreator =
                spy(new OrderFormalOrderCreator(new CheckoutOrderApplicationService(
                        new JpaOrderStore(orderRepository, stockLogRepository, orderLineRepository), customerPort())));
        Fixture fixture = new Fixture(
                new JpaCartCheckoutStore(checkoutRepository, subOrderRepository, lineRepository),
                formalOrderCreator,
                cleanupIntentRepository,
                new JdbcCartCleanupTenantSource(jdbcTemplate),
                transactionManager,
                realInventoryService());
        fixture.seedSelectedCart();
        fixture.failCouponRedemption();

        assertThatThrownBy(() -> fixture.service.checkout(
                        USER,
                        new CartCheckoutRequestDto(9L, "CN-BJ", List.of("PLATFORM-20")),
                        "checkout-key-coupon-rollback"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        verify(formalOrderCreator).create(any());
        assertThat(checkoutRepository.count()).isZero();
        assertThat(subOrderRepository.count()).isZero();
        assertThat(lineRepository.count()).isZero();
        assertThat(orderRepository.count()).isZero();
        assertThat(orderLineRepository.count()).isZero();
        assertThat(reservationRepository.count()).isZero();
        assertThat(inventoryLedgerRepository.count()).isZero();
        assertThat(inventoryStockRepository.findAll()).allSatisfy(stock -> {
            assertThat(stock.getAvailableQuantity()).isEqualTo(10);
            assertThat(stock.getLockedQuantity()).isZero();
        });
        assertThat(cleanupIntentRepository.count()).isZero();
        assertThat(fixture.cartStore.findCart(USER.id()).items()).hasSize(2);
    }

    private JpaCartCleanupIntentStore cleanupIntentStore() {
        return new JpaCartCleanupIntentStore(
                cleanupIntentRepository, new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    private boolean claimAfterBarrier(
            JpaCartCleanupIntentStore store,
            Long checkoutId,
            String claimToken,
            LocalDateTime now,
            LocalDateTime leaseExpiresAt,
            CountDownLatch ready,
            CountDownLatch start)
            throws InterruptedException {
        TenantContext.setTenantId(1L);
        try {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("claim start barrier timed out");
            }
            return inNewTransaction(() -> store.claim(checkoutId, claimToken, now, leaseExpiresAt))
                    .isPresent();
        } finally {
            TenantContext.clear();
        }
    }

    private boolean processAfterBarrier(
            CartCleanupProcessor processor, Long checkoutId, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        TenantContext.setTenantId(1L);
        try {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("processor start barrier timed out");
            }
            return processor.process(checkoutId);
        } finally {
            TenantContext.clear();
        }
    }

    private <T> T inNewTransaction(Supplier<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> action.get());
    }

    private static long positiveRandomId() {
        return UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    }

    private void seedInventory() {
        jdbcTemplate.update(
                "INSERT INTO inventory_warehouse "
                        + "(id, tenant_id, code, name, province, priority, active) VALUES (9001, 1, 'WH-BJ', 'Beijing', 'CN-BJ', 1, TRUE)");
        jdbcTemplate.update("INSERT INTO inventory_stock "
                + "(id, tenant_id, sku_id, warehouse_id, available_quantity, locked_quantity, "
                + "deducted_quantity, in_transit_quantity, safety_stock, version) "
                + "VALUES (9101, 1, 1001, 9001, 10, 0, 0, 0, 0, 0), "
                + "(9102, 1, 1002, 9001, 10, 0, 0, 0, 0, 0)");
    }

    private InventoryApplicationService realInventoryService() {
        return new InventoryApplicationService(
                new JpaInventoryStore(
                        inventoryStockRepository,
                        warehouseRepository,
                        reservationRepository,
                        inventoryLedgerRepository),
                new DirectInventoryLockManager(),
                new AtomicIdGenerator(),
                mock(AuditService.class),
                Duration.ofMinutes(15));
    }

    private static final class Fixture {
        private final InMemoryCartStore cartStore = new InMemoryCartStore();
        private final Map<Long, CartSkuSnapshot> catalog = new ConcurrentHashMap<>();
        private final InventoryApplicationService inventoryApplicationService;
        private final MarketingApplicationService marketingApplicationService = mock(MarketingApplicationService.class);
        private final AuditService auditService = mock(AuditService.class);
        private final CartCleanupProcessor cleanupProcessor;
        private final CartCleanupRetryWorker cleanupRetryWorker;
        private final CartApplicationService service;

        private Fixture(
                JpaCartCheckoutStore checkoutStore,
                OrderFormalOrderCreator formalOrderCreator,
                CartCleanupIntentRepository cleanupIntentRepository,
                CartCleanupTenantSource cleanupTenantSource,
                PlatformTransactionManager transactionManager) {
            this(
                    checkoutStore,
                    formalOrderCreator,
                    cleanupIntentRepository,
                    cleanupTenantSource,
                    transactionManager,
                    mock(InventoryApplicationService.class));
        }

        private Fixture(
                JpaCartCheckoutStore checkoutStore,
                OrderFormalOrderCreator formalOrderCreator,
                CartCleanupIntentRepository cleanupIntentRepository,
                CartCleanupTenantSource cleanupTenantSource,
                PlatformTransactionManager transactionManager,
                InventoryApplicationService inventoryApplicationService) {
            this(
                    checkoutStore,
                    formalOrderCreator,
                    cleanupIntentRepository,
                    cleanupTenantSource,
                    transactionManager,
                    inventoryApplicationService,
                    new DirectCartLockManager());
        }

        private Fixture(
                JpaCartCheckoutStore checkoutStore,
                OrderFormalOrderCreator formalOrderCreator,
                CartCleanupIntentRepository cleanupIntentRepository,
                CartCleanupTenantSource cleanupTenantSource,
                PlatformTransactionManager transactionManager,
                InventoryApplicationService inventoryApplicationService,
                CartLockManager cartLockManager) {
            this.inventoryApplicationService = inventoryApplicationService;
            catalog.put(
                    1001L,
                    new CartSkuSnapshot(1001L, 501L, 11L, "SKU-1001", "Phone", "/phone.png", new BigDecimal("100.00")));
            catalog.put(
                    1002L,
                    new CartSkuSnapshot(
                            1002L, 502L, 12L, "SKU-1002", "Keyboard", "/keyboard.png", new BigDecimal("30.00")));
            if (org.mockito.Mockito.mockingDetails(inventoryApplicationService).isMock()) {
                when(inventoryApplicationService.reserve(any()))
                        .thenAnswer(invocation -> reservation(invocation.getArgument(0)));
            }
            when(marketingApplicationService.quotePlatformPrice(any()))
                    .thenAnswer(invocation -> noDiscount(invocation.getArgument(0)));
            when(marketingApplicationService.quoteStorePrice(any()))
                    .thenAnswer(invocation -> noDiscount(invocation.getArgument(0)));
            JpaCartCleanupIntentStore cleanupIntentStore = new JpaCartCleanupIntentStore(
                    cleanupIntentRepository, new ObjectMapper().registerModule(new JavaTimeModule()));
            cleanupProcessor = new CartCleanupProcessor(cleanupIntentStore, cartStore, transactionManager);
            cleanupRetryWorker = new CartCleanupRetryWorker(cleanupIntentStore, cleanupTenantSource, cleanupProcessor);
            CartApplicationService target = new CartApplicationService(
                    cartStore,
                    skuId -> Optional.ofNullable(catalog.get(skuId)),
                    checkoutStore,
                    new DurableCartCleanupScheduler(cleanupIntentStore, cleanupProcessor),
                    cartLockManager,
                    new RequiresNewCartTransactions(transactionManager),
                    inventoryApplicationService,
                    marketingApplicationService,
                    formalOrderCreator,
                    new AtomicOrderNumberGenerator(),
                    new AtomicIdGenerator(),
                    auditService,
                    Duration.ofDays(7));
            service = target;
        }

        private void pauseNextCommit(CountDownLatch beforeCommit, CountDownLatch releaseCommit) {
            AtomicBoolean pause = new AtomicBoolean(true);
            org.mockito.Mockito.doAnswer(invocation -> {
                        if (pause.compareAndSet(true, false)) {
                            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                                @Override
                                public void beforeCommit(boolean readOnly) {
                                    beforeCommit.countDown();
                                    try {
                                        if (!releaseCommit.await(5, TimeUnit.SECONDS)) {
                                            throw new IllegalStateException("commit release timed out");
                                        }
                                    } catch (InterruptedException exception) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException("commit wait interrupted", exception);
                                    }
                                }
                            });
                        }
                        return null;
                    })
                    .when(auditService)
                    .record(any(), any(), any(), any(), any(), any(), any());
        }

        private void failAuditAfterCartCleanup() {
            org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable"))
                    .when(auditService)
                    .record(any(), any(), any(), any(), any(), any(), any());
        }

        private void failCouponRedemption() {
            org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.CONFLICT, "coupon redemption failed"))
                    .when(marketingApplicationService)
                    .redeemForCheckout(any(), any(), any());
        }

        private void seedSelectedCart() {
            LocalDateTime now = LocalDateTime.now();
            cartStore.seed(new CartSnapshot(
                    USER.id(),
                    List.of(new CartItem(1001L, 1L, 2, true, now, now), new CartItem(1002L, 2L, 1, true, now, now))));
        }

        private static InventoryReservationResponseDto reservation(InventoryReserveRequestDto request) {
            Long warehouseId = 9000L + request.skuId();
            return new InventoryReservationResponseDto(
                    request.reservationKey(),
                    request.skuId(),
                    warehouseId,
                    request.orderId(),
                    request.quantity(),
                    InventoryReservationStatus.RESERVED,
                    LocalDateTime.now().plusMinutes(15),
                    new WarehouseStockResponseDto(
                            request.skuId(), warehouseId, "WH", request.province(), 10, 1, 0, 0, 2, 11, false));
        }

        private static MarketingPriceQuoteDto noDiscount(MarketingPriceRequestDto request) {
            return new MarketingPriceQuoteDto(request.orderAmount(), BigDecimal.ZERO, request.orderAmount(), List.of());
        }
    }

    private static OrderCustomerPort customerPort() {
        OrderCustomerPort customerPort = mock(OrderCustomerPort.class);
        when(customerPort.findBuyerById(USER.id()))
                .thenReturn(Optional.of(new BuyerRecord(USER.id(), "momo", "/avatar.png")));
        when(customerPort.findAddressById(9L))
                .thenReturn(Optional.of(new AddressRecord(9L, USER.id(), "Momo", "13800000000", "Beijing")));
        return customerPort;
    }

    private static final class InMemoryCartStore implements CartStore {
        private final Map<Long, CartSnapshot> carts = new ConcurrentHashMap<>();
        private final AtomicInteger cleanupFailures = new AtomicInteger();
        private final AtomicInteger cleanupAttempts = new AtomicInteger();
        private final AtomicBoolean cleanupRanInsideTransaction = new AtomicBoolean();

        @Override
        public CartSnapshot findCart(Long userId) {
            return carts.getOrDefault(userId, new CartSnapshot(userId, List.of()));
        }

        @Override
        public void putItem(Long userId, CartItem item, Duration ttl) {
            carts.compute(userId, (ignored, current) -> {
                CartSnapshot cart = current == null ? new CartSnapshot(userId, List.of()) : current;
                List<CartItem> items = new java.util.ArrayList<>(cart.items());
                items.removeIf(existing -> existing.skuId().equals(item.skuId()));
                items.add(item);
                return new CartSnapshot(userId, items);
            });
        }

        @Override
        public void removeItem(Long userId, Long skuId, Duration ttl) {
            carts.computeIfPresent(
                    userId,
                    (ignored, cart) -> new CartSnapshot(
                            userId,
                            cart.items().stream()
                                    .filter(item -> !item.skuId().equals(skuId))
                                    .toList()));
        }

        @Override
        public void removeMatchingItems(Long userId, List<CartItem> expectedItems, Duration ttl) {
            cleanupRanInsideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            cleanupAttempts.incrementAndGet();
            if (cleanupFailures.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
                throw new IllegalStateException("cart unavailable");
            }
            carts.computeIfPresent(
                    userId,
                    (ignored, cart) -> new CartSnapshot(
                            userId,
                            cart.items().stream()
                                    .filter(item -> !expectedItems.contains(item))
                                    .toList()));
        }

        private void seed(CartSnapshot cart) {
            carts.put(cart.userId(), cart);
        }

        private void failNextCleanup() {
            cleanupFailures.incrementAndGet();
        }

        private int cleanupAttempts() {
            return cleanupAttempts.get();
        }

        private boolean cleanupRanInsideTransaction() {
            return cleanupRanInsideTransaction.get();
        }
    }

    private static final class FailFirstCompletionIntentStore implements CartCleanupIntentStore {
        private final CartCleanupIntentStore delegate;
        private final AtomicBoolean failCompletion = new AtomicBoolean(true);

        private FailFirstCompletionIntentStore(CartCleanupIntentStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public CartCleanupIntent save(CartCleanupIntent intent) {
            return delegate.save(intent);
        }

        @Override
        public Optional<CartCleanupIntent> findByCheckoutId(Long checkoutId) {
            return delegate.findByCheckoutId(checkoutId);
        }

        @Override
        public Optional<CartCleanupIntent> claim(
                Long checkoutId, String claimToken, LocalDateTime now, LocalDateTime leaseExpiresAt) {
            return delegate.claim(checkoutId, claimToken, now, leaseExpiresAt);
        }

        @Override
        public boolean completeClaim(Long checkoutId, String claimToken, LocalDateTime now) {
            if (failCompletion.compareAndSet(true, false)) {
                throw new IllegalStateException("completion unavailable");
            }
            return delegate.completeClaim(checkoutId, claimToken, now);
        }

        @Override
        public boolean failClaim(
                Long checkoutId, String claimToken, LocalDateTime now, LocalDateTime nextAttemptAt, String error) {
            return delegate.failClaim(checkoutId, claimToken, now, nextAttemptAt, error);
        }

        @Override
        public List<Long> findReadyCheckoutIds(LocalDateTime now) {
            return delegate.findReadyCheckoutIds(now);
        }
    }

    private static final class ObservingCartLockManager implements CartLockManager {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger entries = new AtomicInteger();
        private final CountDownLatch secondEntry = new CountDownLatch(1);

        @Override
        public <T> T withCheckoutLock(Long userId, String idempotencyKey, Supplier<T> action) {
            lock.lock();
            try {
                if (entries.incrementAndGet() == 2) {
                    secondEntry.countDown();
                }
                return action.get();
            } finally {
                lock.unlock();
            }
        }

        private boolean awaitSecondEntry(long timeout, TimeUnit unit) throws InterruptedException {
            return secondEntry.await(timeout, unit);
        }
    }

    private static final class DirectCartLockManager implements CartLockManager {
        @Override
        public <T> T withCheckoutLock(Long userId, String idempotencyKey, Supplier<T> action) {
            return action.get();
        }
    }

    private static final class DirectInventoryLockManager implements InventoryLockManager {
        @Override
        public <T> T withStockLock(Long skuId, Long warehouseId, Supplier<T> action) {
            return action.get();
        }
    }

    private static final class AtomicIdGenerator implements IdGenerator {
        private final AtomicLong next = new AtomicLong(1_000L);

        @Override
        public long nextId() {
            return next.incrementAndGet();
        }
    }

    private static final class AtomicOrderNumberGenerator implements OrderNumberGenerator {
        private final AtomicLong next = new AtomicLong(1_000L);

        @Override
        public String nextOrderNo() {
            return "ORD" + next.incrementAndGet();
        }
    }
}
