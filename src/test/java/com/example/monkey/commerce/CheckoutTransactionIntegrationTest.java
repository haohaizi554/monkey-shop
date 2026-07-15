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
import com.example.monkey.cart.domain.CartCleanupTenantSource;
import com.example.monkey.cart.domain.CartItem;
import com.example.monkey.cart.domain.CartLockManager;
import com.example.monkey.cart.domain.CartSkuSnapshot;
import com.example.monkey.cart.domain.CartSnapshot;
import com.example.monkey.cart.domain.CartStore;
import com.example.monkey.cart.infrastructure.CartCheckoutLineRepository;
import com.example.monkey.cart.infrastructure.CartCheckoutRepository;
import com.example.monkey.cart.infrastructure.CartCleanupIntentRepository;
import com.example.monkey.cart.infrastructure.CartSubOrderRepository;
import com.example.monkey.cart.infrastructure.JdbcCartCleanupTenantSource;
import com.example.monkey.cart.infrastructure.JpaCartCheckoutStore;
import com.example.monkey.cart.infrastructure.JpaCartCleanupIntentStore;
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
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;

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

        assertThat(AopUtils.isAopProxy(fixture.service)).isTrue();
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
            JpaCartCleanupIntentStore cleanupIntentStore = new JpaCartCleanupIntentStore(cleanupIntentRepository);
            cleanupProcessor = new CartCleanupProcessor(cleanupIntentStore, cartStore, transactionManager);
            cleanupRetryWorker = new CartCleanupRetryWorker(cleanupIntentStore, cleanupTenantSource, cleanupProcessor);
            CartApplicationService target = new CartApplicationService(
                    cartStore,
                    skuId -> Optional.ofNullable(catalog.get(skuId)),
                    checkoutStore,
                    new DurableCartCleanupScheduler(cleanupIntentStore, cleanupProcessor),
                    new DirectCartLockManager(),
                    inventoryApplicationService,
                    marketingApplicationService,
                    formalOrderCreator,
                    new AtomicOrderNumberGenerator(),
                    new AtomicIdGenerator(),
                    auditService,
                    Duration.ofDays(7));
            ProxyFactory proxyFactory = new ProxyFactory(target);
            proxyFactory.addAdvice(
                    new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
            service = (CartApplicationService) proxyFactory.getProxy();
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
            cartStore.save(
                    new CartSnapshot(
                            USER.id(),
                            List.of(
                                    new CartItem(1001L, 1L, 2, true, now, now),
                                    new CartItem(1002L, 2L, 1, true, now, now))),
                    Duration.ofDays(7));
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

        @Override
        public CartSnapshot findCart(Long userId) {
            return carts.getOrDefault(userId, new CartSnapshot(userId, List.of()));
        }

        @Override
        public CartSnapshot save(CartSnapshot cart, Duration ttl) {
            carts.put(cart.userId(), cart);
            return cart;
        }

        @Override
        public void removeItems(Long userId, List<Long> skuIds, Duration ttl) {
            cleanupAttempts.incrementAndGet();
            if (cleanupFailures.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
                throw new IllegalStateException("cart unavailable");
            }
            CartSnapshot cart = findCart(userId);
            for (Long skuId : skuIds) {
                cart = cart.remove(skuId);
            }
            save(cart, ttl);
        }

        private void failNextCleanup() {
            cleanupFailures.incrementAndGet();
        }

        private int cleanupAttempts() {
            return cleanupAttempts.get();
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
